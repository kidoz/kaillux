package su.kidoz.net;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import su.kidoz.util.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A UDP server that binds to multiple addresses (e.g., IPv4 and IPv6) on the
 * same port.
 *
 * <p>
 * Each address gets its own DatagramChannel, running in its own thread. All
 * channels share the same port number and delegate received packets to a common
 * handler.
 */
public abstract class MultiAddressUDPServer implements Executable {
    private static final Logger log = LoggerFactory.getLogger(MultiAddressUDPServer.class);

    private final List<ChannelHandler> handlers = new CopyOnWriteArrayList<>();
    private final boolean shutdownOnExit;
    private Executor executor;
    private int bindPort;
    private volatile boolean isRunning = false;
    private volatile boolean stopFlag = false;

    public MultiAddressUDPServer() {
        this(true);
    }

    public MultiAddressUDPServer(boolean shutdownOnExit) {
        this.shutdownOnExit = shutdownOnExit;
        if (shutdownOnExit) {
            Runtime.getRuntime().addShutdownHook(new ShutdownThread());
        }
    }

    protected void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public int getBindPort() {
        return bindPort;
    }

    public List<InetAddress> getBindAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        for (ChannelHandler handler : handlers) {
            addresses.add(handler.bindAddress);
        }
        return addresses;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public synchronized boolean isBound() {
        return !handlers.isEmpty() && handlers.stream().allMatch(h -> h.isBound());
    }

    public synchronized void start() {
        log.debug("{} received start request!", this);
        if (isRunning) {
            log.debug("{} start request ignored: already running!", this);
            return;
        }
        if (handlers.isEmpty()) {
            log.warn("{} start request ignored: not bound", this);
            return;
        }

        // Latch the state before any worker runs. A concurrent stop() must see a
        // running server and perform cleanup instead of returning early.
        stopFlag = false;
        isRunning = true;
        for (ChannelHandler handler : handlers) {
            executeHandler(handler);
        }
    }

    protected boolean getStopFlag() {
        return stopFlag;
    }

    public synchronized void stop() {
        stopFlag = true;
        isRunning = false;
        for (ChannelHandler handler : handlers) {
            handler.stop();
        }
        // Channels cannot be rebound after close. A later start() binds a fresh set.
        handlers.clear();
    }

    protected synchronized void bind(int port, List<InetAddress> addresses) throws BindException {
        if (addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("At least one bind address is required");
        }

        this.bindPort = port;

        try {
            for (InetAddress address : addresses) {
                ChannelHandler handler = new ChannelHandler(address);
                handler.bind(port);
                handlers.add(handler);
            }
        } catch (BindException e) {
            // Do not leave earlier address families bound when a later bind fails.
            for (ChannelHandler handler : handlers) {
                handler.stop();
            }
            handlers.clear();
            throw e;
        }
    }

    protected abstract ByteBuffer getBuffer();

    protected abstract void releaseBuffer(ByteBuffer buffer);

    protected abstract void handleReceived(ByteBuffer buffer,
            InetSocketAddress remoteSocketAddress);

    protected void send(ByteBuffer buffer, InetSocketAddress toSocketAddress) {
        InetAddress targetAddress = toSocketAddress.getAddress();

        // Find appropriate channel for the target address type
        ChannelHandler handler = findHandlerForAddress(targetAddress);
        if (handler != null) {
            handler.send(buffer, toSocketAddress);
        } else {
            log.warn("No suitable channel found for sending to {}", toSocketAddress);
        }
    }

    private ChannelHandler findHandlerForAddress(InetAddress targetAddress) {
        boolean targetIsIPv6 = targetAddress instanceof Inet6Address;

        // First try to find exact match
        for (ChannelHandler handler : handlers) {
            boolean handlerIsIPv6 = handler.bindAddress instanceof Inet6Address;
            if (targetIsIPv6 == handlerIsIPv6 && handler.isBound()) {
                return handler;
            }
        }

        // Fall back to any available handler
        for (ChannelHandler handler : handlers) {
            if (handler.isBound()) {
                return handler;
            }
        }

        return null;
    }

    private void executeHandler(ChannelHandler handler) {
        if (executor != null) {
            executor.execute(handler);
        } else {
            new Thread(handler, "UDP-" + handler.bindAddress.getHostAddress()).start();
        }
    }

    @Override
    public void run() {
        start();
    }

    private class ChannelHandler implements Runnable {
        private final InetAddress bindAddress;
        private DatagramChannel channel;

        ChannelHandler(InetAddress bindAddress) {
            this.bindAddress = bindAddress;
        }

        void bind(int port) throws BindException {
            try {
                if (bindAddress instanceof Inet6Address) {
                    channel = DatagramChannel.open(StandardProtocolFamily.INET6);
                } else {
                    channel = DatagramChannel.open(StandardProtocolFamily.INET);
                }

                InetSocketAddress socketAddress = new InetSocketAddress(bindAddress, port);
                channel.socket().bind(socketAddress);

                ByteBuffer tempBuffer = getBuffer();
                int bufferSize = tempBuffer.capacity() * 2;
                releaseBuffer(tempBuffer);

                channel.socket().setReceiveBufferSize(bufferSize);
                channel.socket().setSendBufferSize(bufferSize);
                // No socket timeout: it has no effect on a blocking DatagramChannel.
                // Shutdown relies on stop() closing the channel, which wakes receive().

                log.info("Bound to {}:{}", bindAddress.getHostAddress(), port);
            } catch (IOException e) {
                // The channel was opened but not bound; close it or the failed
                // attempt leaks a descriptor on every retry.
                stop();
                throw new BindException(
                        "Failed to bind to " + bindAddress.getHostAddress() + ":" + port, port, e);
            }
        }

        boolean isBound() {
            return channel != null && channel.socket() != null && !channel.socket().isClosed();
        }

        void stop() {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    log.error("Failed to close channel for {}: {}", bindAddress.getHostAddress(),
                            e.getMessage());
                }
            }
        }

        void send(ByteBuffer buffer, InetSocketAddress toSocketAddress) {
            if (!isBound()) {
                log.warn("Failed to send: channel for {} is not bound",
                        bindAddress.getHostAddress());
                return;
            }

            try {
                channel.send(buffer, toSocketAddress);
            } catch (Exception e) {
                log.error("Failed to send on {}: {}", bindAddress.getHostAddress(), e.getMessage());
            }
        }

        @Override
        public void run() {
            log.debug("Handler thread starting for {}", bindAddress.getHostAddress());

            try {
                while (!stopFlag) {
                    try {
                        ByteBuffer buffer = getBuffer();
                        InetSocketAddress fromSocketAddress = (InetSocketAddress) channel
                                .receive(buffer);

                        if (stopFlag) {
                            break;
                        }

                        if (fromSocketAddress == null) {
                            throw new IOException("receive returned null");
                        }

                        buffer.flip();
                        handleReceived(buffer, fromSocketAddress);
                        releaseBuffer(buffer);
                    } catch (ClosedChannelException e) {
                        log.debug("Channel closed for {}", bindAddress.getHostAddress());
                        break;
                    } catch (SocketException e) {
                        if (stopFlag) {
                            break;
                        }
                        log.error("Socket error on {}: {}", bindAddress.getHostAddress(),
                                e.getMessage());
                    } catch (IOException e) {
                        if (stopFlag) {
                            break;
                        }
                        log.error("IO error on {}: {}", bindAddress.getHostAddress(),
                                e.getMessage());
                    }
                }
            } catch (RuntimeException e) {
                log.error("Unexpected error on {}", bindAddress.getHostAddress(), e);
                // Stop the whole server: leaving isRunning true would report a
                // healthy listener while one address family is dead.
                MultiAddressUDPServer.this.stop();
            } finally {
                log.debug("Handler thread exiting for {}", bindAddress.getHostAddress());
            }
        }
    }

    private final class ShutdownThread extends Thread {
        @Override
        public void run() {
            MultiAddressUDPServer.this.stop();
        }
    }
}
