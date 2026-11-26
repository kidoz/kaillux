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

import su.kidoz.util.EmuUtil;
import su.kidoz.util.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class UDPServer implements Executable {
    private static final Logger log = LoggerFactory.getLogger(UDPServer.class);

    private int bindPort;
    private InetAddress bindAddress;
    // Read by the receive loop without the monitor; stop() closes it from another
    // thread, which is what wakes a receive() blocked in that loop.
    private volatile DatagramChannel channel;
    private volatile boolean isRunning = false;
    private volatile boolean stopFlag = false;

    public UDPServer() {
        this(true);
    }

    public UDPServer(boolean shutdownOnExit) {
        if (shutdownOnExit)
            Runtime.getRuntime().addShutdownHook(new ShutdownThread());
    }

    public int getBindPort() {
        return bindPort;
    }

    public InetAddress getBindAddress() {
        return bindAddress;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public synchronized boolean isBound() {
        if (channel == null)
            return false;
        if (channel.socket() == null)
            return false;
        return !channel.socket().isClosed();
    }

    public boolean isConnected() {
        return channel != null && channel.isConnected();
    }

    public synchronized void start() {
        log.debug(toString() + " received start request!");
        if (isRunning) {
            log.debug(toString() + " start request ignored: already running!");
            return;
        }

        stopFlag = false;
    }

    protected boolean getStopFlag() {
        return stopFlag;
    }

    public synchronized void stop() {
        stopFlag = true;

        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                log.error("Failed to close DatagramChannel: " + e.getMessage());
            }
        }
    }

    protected synchronized void bind() throws BindException {
        bind(-1, null);
    }

    protected synchronized void bind(int port) throws BindException {
        bind(port, null);
    }

    protected synchronized void bind(int port, InetAddress address) throws BindException {
        try {
            // Determine protocol family based on address type
            if (address instanceof Inet6Address) {
                channel = DatagramChannel.open(StandardProtocolFamily.INET6);
            } else {
                channel = DatagramChannel.open(StandardProtocolFamily.INET);
            }

            InetSocketAddress socketAddress;
            if (port > 0) {
                if (address != null) {
                    socketAddress = new InetSocketAddress(address, port);
                } else {
                    socketAddress = new InetSocketAddress(port);
                }
            } else {
                if (address != null) {
                    socketAddress = new InetSocketAddress(address, 0);
                } else {
                    socketAddress = null;
                }
            }

            channel.socket().bind(socketAddress);
            bindPort = channel.socket().getLocalPort();
            bindAddress = address;

            ByteBuffer tempBuffer = getBuffer();
            int bufferSize = (tempBuffer.capacity() * 2);
            releaseBuffer(tempBuffer);

            channel.socket().setReceiveBufferSize(bufferSize);
            channel.socket().setSendBufferSize(bufferSize);
            // No socket timeout: it has no effect on a blocking DatagramChannel.
            // Shutdown relies on stop() closing the channel, which wakes receive().
        } catch (IOException e) {
            String addressStr = address != null ? address.getHostAddress() + ":" : "";
            throw new BindException("Failed to bind to " + addressStr + port, port, e);
        }

        this.start();
    }

    protected abstract ByteBuffer getBuffer();

    protected abstract void releaseBuffer(ByteBuffer buffer);

    protected abstract void handleReceived(ByteBuffer buffer,
            InetSocketAddress remoteSocketAddress);

    protected void send(ByteBuffer buffer, InetSocketAddress toSocketAddress) {
        if (!isBound()) {
            log.warn("Failed to send to " + EmuUtil.formatSocketAddress(toSocketAddress)
                    + ": UDPServer is not bound!");
            return;
        }

        try {
            channel.send(buffer, toSocketAddress);
        } catch (Exception e) {
            log.error("Failed to send on port " + getBindPort() + ": " + e.getMessage(), e);
        }
    }

    public void run() {
        isRunning = true;
        log.debug(toString() + ": thread running...");

        try {
            while (!stopFlag) {
                ByteBuffer buffer = null;
                try {
                    buffer = getBuffer();
                    InetSocketAddress fromSocketAddress = (InetSocketAddress) channel
                            .receive(buffer);

                    if (stopFlag)
                        break;

                    if (fromSocketAddress == null)
                        throw new IOException(
                                "Failed to receive from DatagramChannel: fromSocketAddress == null");

                    buffer.flip();
                    handleReceived(buffer, fromSocketAddress);
                } catch (ClosedChannelException e) {
                    // Channel was closed (expected during shutdown)
                    // Note: AsynchronousCloseException is a subclass of ClosedChannelException
                    log.debug("Channel closed on port {}", getBindPort());
                    break;
                } catch (SocketException e) {
                    if (stopFlag)
                        break;

                    log.error("Failed to receive on port " + getBindPort() + ": " + e.getMessage());
                } catch (IOException e) {
                    if (stopFlag)
                        break;

                    log.error("Failed to receive on port " + getBindPort() + ": " + e.getMessage());
                } finally {
                    if (buffer != null) {
                        releaseBuffer(buffer);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("UDPServer on port " + getBindPort() + " caught unexpected exception!", e);
            stop();
            afterUnexpectedExit();
        } finally {
            isRunning = false;
            log.debug(toString() + ": thread exiting...");
        }
    }

    /**
     * Called on the receive thread after an unexpected exception ended the loop and
     * the socket was closed. The default does nothing; subclasses override it to
     * release whatever depended on the loop.
     */
    protected void afterUnexpectedExit() {
    }

    private final class ShutdownThread extends Thread {
        private ShutdownThread() {
        }

        public void run() {
            UDPServer.this.stop();
        }
    }
}
