package su.kidoz.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.ThrottledLogger;

/**
 * Abstract UDP packet relay that acts as a transparent proxy between clients
 * and a backend server.
 *
 * <p>
 * This class implements the {@link SmartLifecycle} interface for Spring-managed
 * lifecycle control. Subclasses must implement {@link #processClientToServer}
 * and {@link #processServerToClient} to handle protocol-specific message
 * processing.
 *
 * <p>
 * The relay uses NIO-based {@link DatagramChannel} for high-performance UDP
 * packet handling and manages per-client handler threads for bidirectional
 * traffic relay.
 */
public abstract class UDPRelay implements SmartLifecycle, Runnable {

    protected static final Logger log = LoggerFactory.getLogger(UDPRelay.class);

    private static final int DEFAULT_MAX_CONNECTIONS = 100;
    private static final int DEFAULT_BUFFER_SIZE = 2048;

    /** Selector timeout and interval between idle-client checks. */
    private static final long IDLE_CHECK_INTERVAL_MS = 1000L;

    /**
     * Cap on datagrams drained per selector wakeup so cleanup still runs under
     * load.
     */
    private static final int MAX_PACKETS_PER_ITERATION = 256;

    /**
     * Idle timeout after which an established client handler is closed and removed.
     */
    public static final long DEFAULT_CLIENT_IDLE_TIMEOUT_MS = 300_000L;

    /**
     * Idle timeout for a flow whose client has sent a single datagram. A real
     * client follows its first packet as soon as the backend answers; a spoofed or
     * abandoned source never does, so its state must not be held for the full idle
     * period.
     */
    public static final long SINGLE_PACKET_IDLE_TIMEOUT_MS = 15_000L;

    /** Fewest flows one source address may hold. */
    private static final int MIN_CONNECTIONS_PER_ADDRESS = 4;

    // Any host can open flows or send junk to a relay, so these may not be written
    // once per packet.
    private static final ThrottledLogger LIMIT_LOG = new ThrottledLogger(log,
            "relay connection limit");
    private static final ThrottledLogger PACKET_FAILURE_LOG = new ThrottledLogger(log,
            "failed relay packet");
    private static final ThrottledLogger HANDLER_START_LOG = new ThrottledLogger(log,
            "failed to start a relay client handler");

    private final ExecutorService threadPool;
    private final int listenPort;
    private final InetSocketAddress serverSocketAddress;
    private final int maxConnections;
    private final int bufferSize;

    /** Flows one source address may hold at the same time. */
    private final int maxConnectionsPerAddress;
    private final long singlePacketIdleTimeoutMs;
    private final long clientIdleTimeoutMs;

    private DatagramChannel listenChannel;
    private InetAddress listenBindAddress;
    private volatile Selector selector;
    private volatile boolean running = false;
    private volatile boolean stopFlag = false;

    private final Map<InetSocketAddress, ClientHandler> clients = Collections
            .synchronizedMap(new HashMap<>());

    // Metrics
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong lastActivityTime = new AtomicLong(0);
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicLong bytesRelayed = new AtomicLong(0);
    private final AtomicInteger parseErrors = new AtomicInteger(0);

    /**
     * Creates a new UDP relay.
     *
     * @param threadPool
     *            the executor service for handling client connections
     * @param listenPort
     *            the port to listen on for incoming connections
     * @param serverSocketAddress
     *            the backend server address to relay traffic to
     */
    public UDPRelay(ExecutorService threadPool, int listenPort,
            InetSocketAddress serverSocketAddress) {
        this(threadPool, listenPort, serverSocketAddress, DEFAULT_MAX_CONNECTIONS,
                DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates a new UDP relay with custom settings.
     *
     * @param threadPool
     *            the executor service for handling client connections
     * @param listenPort
     *            the port to listen on for incoming connections
     * @param serverSocketAddress
     *            the backend server address to relay traffic to
     * @param maxConnections
     *            maximum concurrent connections
     * @param bufferSize
     *            buffer size for UDP packets
     */
    public UDPRelay(ExecutorService threadPool, int listenPort,
            InetSocketAddress serverSocketAddress, int maxConnections, int bufferSize) {
        this(threadPool, listenPort, serverSocketAddress, maxConnections, bufferSize,
                SINGLE_PACKET_IDLE_TIMEOUT_MS, DEFAULT_CLIENT_IDLE_TIMEOUT_MS);
    }

    /**
     * Creates a new UDP relay with explicit idle timeouts.
     *
     * @param threadPool
     *            the executor service for handling client connections
     * @param listenPort
     *            the port to listen on for incoming connections
     * @param serverSocketAddress
     *            the backend server address to relay traffic to
     * @param maxConnections
     *            maximum concurrent connections
     * @param bufferSize
     *            buffer size for UDP packets
     * @param singlePacketIdleTimeoutMs
     *            idle timeout for a flow whose client sent one datagram
     * @param clientIdleTimeoutMs
     *            idle timeout for every other flow
     */
    protected UDPRelay(ExecutorService threadPool, int listenPort,
            InetSocketAddress serverSocketAddress, int maxConnections, int bufferSize,
            long singlePacketIdleTimeoutMs, long clientIdleTimeoutMs) {
        this.threadPool = threadPool;
        this.listenPort = listenPort;
        this.serverSocketAddress = serverSocketAddress;
        this.maxConnections = maxConnections;
        this.bufferSize = bufferSize;
        // A fifth of the table, so that a handful of addresses cannot fill it.
        this.maxConnectionsPerAddress = Math.max(MIN_CONNECTIONS_PER_ADDRESS, maxConnections / 5);
        this.singlePacketIdleTimeoutMs = singlePacketIdleTimeoutMs;
        this.clientIdleTimeoutMs = clientIdleTimeoutMs;
    }

    /**
     * Returns the port this relay is listening on.
     */
    public int getListenPort() {
        return listenPort;
    }

    /**
     * Returns the backend server address.
     */
    public InetSocketAddress getServerSocketAddress() {
        return serverSocketAddress;
    }

    /**
     * Returns the listen channel (for subclasses that need direct access).
     */
    protected DatagramChannel getListenChannel() {
        return listenChannel;
    }

    /**
     * Returns the current number of active client connections.
     */
    public int getActiveConnections() {
        return clients.size();
    }

    /**
     * Returns all active client handlers.
     */
    public Collection<ClientHandler> getClientHandlers() {
        return Collections.unmodifiableCollection(clients.values());
    }

    /**
     * Returns the total number of connections handled since startup.
     */
    public int getTotalConnections() {
        return totalConnections.get();
    }

    /**
     * Returns the total bytes relayed since startup.
     */
    public long getBytesRelayed() {
        return bytesRelayed.get();
    }

    /**
     * Returns the number of parse errors encountered.
     */
    public int getParseErrors() {
        return parseErrors.get();
    }

    /**
     * Returns the relay start time in milliseconds since epoch.
     */
    public long getStartTime() {
        return startTime.get();
    }

    /**
     * Returns the last activity time in milliseconds since epoch.
     */
    public long getLastActivityTime() {
        return lastActivityTime.get();
    }

    /**
     * Returns true if this relay has been idle for longer than the specified
     * timeout. A relay is idle if no packets have been relayed for the duration.
     *
     * @param timeoutMs
     *            idle timeout in milliseconds
     * @return true if idle for longer than timeout
     */
    public boolean isIdle(long timeoutMs) {
        long lastActivity = lastActivityTime.get();
        if (lastActivity == 0) {
            // Never had activity, use start time
            lastActivity = startTime.get();
        }
        return lastActivity > 0 && (System.currentTimeMillis() - lastActivity) > timeoutMs;
    }

    /**
     * Increments the parse error counter.
     */
    protected void incrementParseErrors() {
        parseErrors.incrementAndGet();
    }

    /**
     * Processes a packet from client to server. Subclasses can inspect, modify, or
     * reject packets.
     *
     * @param receiveBuffer
     *            the received packet data
     * @param fromAddress
     *            the client's address
     * @param toAddress
     *            the server's address
     * @return the buffer to send to server, or null to drop the packet
     */
    protected abstract ByteBuffer processClientToServer(ByteBuffer receiveBuffer,
            InetSocketAddress fromAddress, InetSocketAddress toAddress);

    /**
     * Processes a packet from server to client. Subclasses can inspect, modify, or
     * reject packets.
     *
     * @param receiveBuffer
     *            the received packet data
     * @param fromAddress
     *            the server's address
     * @param toAddress
     *            the client's address
     * @return the buffer to send to client, or null to drop the packet
     */
    protected abstract ByteBuffer processServerToClient(ByteBuffer receiveBuffer,
            InetSocketAddress fromAddress, InetSocketAddress toAddress);

    /**
     * Called when a new V086 relay should be created for a dynamically allocated
     * port. Subclasses can override this to spawn additional relay instances.
     *
     * @param port
     *            the port number for the new relay
     */
    protected void onDynamicPortAllocated(int port) {
        // Default: no-op. Subclasses can override.
    }

    /**
     * Restricts the listen socket to one local address. Must be called before
     * {@link #start()}.
     *
     * @param address
     *            the local address, or null for the wildcard address
     */
    public synchronized void setListenBindAddress(InetAddress address) {
        if (running) {
            throw new IllegalStateException("Cannot change the bind address of a running relay");
        }
        if (address == null) {
            this.listenBindAddress = null;
            return;
        }
        try {
            // InetAddress is not treated as immutable by static analysis; take a
            // private copy so callers cannot change the relay's bind target.
            this.listenBindAddress = InetAddress.getByAddress(address.getAddress());
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Invalid relay bind address", e);
        }
    }

    // SmartLifecycle implementation

    @Override
    public synchronized void start() {
        if (running) {
            log.debug("{} start request ignored: already running", this);
            return;
        }

        DatagramChannel channel = null;
        try {
            channel = DatagramChannel.open();
            // Non-blocking so the main loop can periodically evict idle client
            // handlers even when no packets arrive.
            channel.configureBlocking(false);
            channel.socket().bind(new InetSocketAddress(listenBindAddress, listenPort));
            listenChannel = channel;
            log.info("{} bound to {}:{}", this,
                    listenBindAddress == null
                            ? "all interfaces"
                            : listenBindAddress.getHostAddress(),
                    listenPort);

            stopFlag = false;
            startTime.set(System.currentTimeMillis());
            running = true;

            threadPool.execute(this);
            log.info("{} started, relaying to {}", this, serverSocketAddress);
        } catch (Exception e) {
            log.error("Failed to start {}: {}", this, e.getMessage(), e);
            running = false;
            stopFlag = true;
            // A failed start must not leave the socket bound: stop() previously
            // returned early because running was already false.
            if (channel != null) {
                try {
                    channel.close();
                } catch (Exception closeFailure) {
                    log.debug("Error closing listen channel after failed start", closeFailure);
                }
            }
            listenChannel = null;
        }
    }

    @Override
    public synchronized void stop() {
        if (!running && listenChannel == null) {
            return;
        }

        log.info("{} stopping...", this);
        stopFlag = true;

        Selector currentSelector = selector;
        if (currentSelector != null) {
            currentSelector.wakeup();
        }

        try {
            if (listenChannel != null) {
                listenChannel.close();
            }
        } catch (Exception e) {
            log.debug("Error closing listen channel during stop", e);
        } finally {
            listenChannel = null;
        }

        // Close all client handlers
        synchronized (clients) {
            for (ClientHandler handler : clients.values()) {
                handler.close();
            }
            clients.clear();
        }

        running = false;
        log.info("{} stopped", this);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Phase 25: After domain model (20), before protocol handlers (28-30)
        return 25;
    }

    @Override
    public void run() {
        log.info("{} main thread running...", this);

        try {
            selector = Selector.open();
            listenChannel.register(selector, SelectionKey.OP_READ);

            while (!stopFlag) {
                // Blocks until a datagram is ready, with a periodic wakeup so idle
                // handlers can be evicted even when no traffic arrives.
                selector.select(IDLE_CHECK_INTERVAL_MS);
                evictIdleClients();

                for (int i = 0; i < MAX_PACKETS_PER_ITERATION && !stopFlag; i++) {
                    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
                    InetSocketAddress clientAddress = (InetSocketAddress) listenChannel
                            .receive(buffer);
                    if (clientAddress == null) {
                        break;
                    }
                    handleClientPacket(clientAddress, buffer);
                }
            }
        } catch (Exception e) {
            if (!stopFlag) {
                log.error("{} main thread caught exception: {}", this, e.getMessage(), e);
            }
        } finally {
            running = false;
            try {
                if (selector != null) {
                    selector.close();
                }
            } catch (Exception e) {
                log.debug("Error closing selector", e);
            }
            try {
                if (listenChannel != null) {
                    listenChannel.close();
                }
            } catch (Exception e) {
                log.debug("Error closing listen channel", e);
            }
        }

        log.info("{} main thread exiting...", this);
    }

    private void handleClientPacket(InetSocketAddress clientAddress, ByteBuffer buffer) {
        buffer.flip();
        ClientHandler clientHandler = clients.get(clientAddress);
        if (clientHandler != null) {
            try {
                clientHandler.send(buffer);
            } catch (Exception e) {
                logPacketFailure(clientAddress, e);
            }
            return;
        }

        if (clients.size() >= maxConnections) {
            LIMIT_LOG.warn("Connection limit reached ({}), rejecting client {}", maxConnections,
                    EmuUtil.formatSocketAddress(clientAddress));
            return;
        }

        if (countClientsFrom(clientAddress.getAddress()) >= maxConnectionsPerAddress) {
            LIMIT_LOG.warn("Per-address connection limit reached ({}), rejecting client {}",
                    maxConnectionsPerAddress, EmuUtil.formatSocketAddress(clientAddress));
            return;
        }

        // Nothing is allocated until the datagram has been examined. Any host can send
        // one datagram from any number of source ports, and a socket and thread for
        // each would fill the table in moments.
        ByteBuffer firstPacket;
        try {
            firstPacket = processClientToServer(buffer, clientAddress, serverSocketAddress);
        } catch (Exception e) {
            logPacketFailure(clientAddress, e);
            return;
        }
        if (firstPacket == null) {
            return;
        }

        try {
            clientHandler = new ClientHandler(clientAddress);
        } catch (Exception e) {
            HANDLER_START_LOG.error("Failed to start new ClientHandler for {}",
                    EmuUtil.formatSocketAddress(clientAddress), e);
            return;
        }

        clients.put(clientAddress, clientHandler);
        totalConnections.incrementAndGet();
        try {
            clientHandler.forward(firstPacket);
        } catch (Exception e) {
            logPacketFailure(clientAddress, e);
        }
        threadPool.execute(clientHandler);
    }

    private int countClientsFrom(InetAddress address) {
        int count = 0;
        synchronized (clients) {
            for (InetSocketAddress clientAddress : clients.keySet()) {
                if (clientAddress.getAddress().equals(address)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void logPacketFailure(InetSocketAddress clientAddress, Exception e) {
        // One malformed packet must not stop the relay listener, and many must not
        // fill the log either.
        incrementParseErrors();
        PACKET_FAILURE_LOG.error("Failed to process packet from {}: {}",
                EmuUtil.formatSocketAddress(clientAddress), e.toString(), e);
    }

    /**
     * Closes client handlers that have seen no traffic in either direction for
     * longer than their idle timeout.
     */
    private void evictIdleClients() {
        long now = System.currentTimeMillis();

        List<ClientHandler> handlers;
        synchronized (clients) {
            handlers = new ArrayList<>(clients.values());
        }

        for (ClientHandler handler : handlers) {
            if (handler.isIdle(now, singlePacketIdleTimeoutMs, clientIdleTimeoutMs)) {
                log.debug("Closing idle relay client {}",
                        EmuUtil.formatSocketAddress(handler.getClientSocketAddress()));
                handler.close();
            }
        }
    }

    /**
     * Handles bidirectional communication for a single client connection.
     */
    protected class ClientHandler implements Runnable {

        private final InetSocketAddress clientSocketAddress;
        private final DatagramChannel clientChannel;
        private final long connectTime;
        private volatile long lastActivity;
        private final AtomicInteger packetsFromClient = new AtomicInteger();
        private volatile boolean closed = false;

        protected ClientHandler(InetSocketAddress clientSocketAddress) throws Exception {
            this.clientSocketAddress = clientSocketAddress;
            this.connectTime = System.currentTimeMillis();
            this.lastActivity = this.connectTime;
            clientChannel = DatagramChannel.open();
            clientChannel.socket().bind(null);
            log.debug("ClientHandler for {} bound to port {}",
                    EmuUtil.formatSocketAddress(clientSocketAddress),
                    clientChannel.socket().getPort());
        }

        /**
         * Returns the client's socket address.
         */
        public InetSocketAddress getClientSocketAddress() {
            return clientSocketAddress;
        }

        /**
         * Returns the connection time in milliseconds since epoch.
         */
        public long getConnectTime() {
            return connectTime;
        }

        /**
         * Returns the time of the last packet activity in milliseconds since epoch.
         */
        public long getLastActivity() {
            return lastActivity;
        }

        /**
         * Returns true if this handler has seen no traffic for its timeout. A flow
         * whose client sent one datagram uses the shorter timeout: a spoofed or
         * abandoned source never sends a second one.
         *
         * @param now
         *            the current time in milliseconds
         * @param singlePacketTimeoutMs
         *            timeout for a flow with one datagram from its client
         * @param timeoutMs
         *            timeout for every other flow
         * @return true if the flow has been idle for longer than its timeout
         */
        protected boolean isIdle(long now, long singlePacketTimeoutMs, long timeoutMs) {
            long timeout = packetsFromClient.get() <= 1 ? singlePacketTimeoutMs : timeoutMs;
            return (now - lastActivity) > timeout;
        }

        private void touch() {
            long now = System.currentTimeMillis();
            lastActivity = now;
            lastActivityTime.set(now);
        }

        /**
         * Examines a packet from the client and relays it when it is accepted.
         *
         * @param buffer
         *            the received packet
         * @throws Exception
         *             if the packet cannot be processed or sent
         */
        protected void send(ByteBuffer buffer) throws Exception {
            ByteBuffer newBuffer = processClientToServer(buffer, clientSocketAddress,
                    serverSocketAddress);
            if (newBuffer != null) {
                forward(newBuffer);
            }
        }

        /**
         * Relays a packet that {@link #processClientToServer} already accepted.
         *
         * @param newBuffer
         *            the packet to relay
         * @throws Exception
         *             if the packet cannot be sent
         */
        protected void forward(ByteBuffer newBuffer) throws Exception {
            int bytes = clientChannel.send(newBuffer, serverSocketAddress);
            bytesRelayed.addAndGet(bytes);
            packetsFromClient.incrementAndGet();
            touch();
        }

        /**
         * Closes this client handler.
         */
        public void close() {
            closed = true;
            try {
                clientChannel.close();
            } catch (Exception e) {
                log.debug("Error closing client channel", e);
            }
        }

        @Override
        public void run() {
            log.debug("ClientHandler thread for {} running...",
                    EmuUtil.formatSocketAddress(clientSocketAddress));

            try {
                while (!stopFlag && !closed) {
                    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
                    InetSocketAddress receiveAddress = (InetSocketAddress) clientChannel
                            .receive(buffer);

                    if (receiveAddress == null) {
                        continue;
                    }

                    // Validate both address AND port to prevent injection from other
                    // processes on the backend host
                    if (!receiveAddress.equals(serverSocketAddress)) {
                        continue;
                    }

                    buffer.flip();
                    touch();

                    try {
                        ByteBuffer newBuffer = processServerToClient(buffer, receiveAddress,
                                clientSocketAddress);
                        if (newBuffer != null) {
                            int bytes = listenChannel.send(newBuffer, clientSocketAddress);
                            bytesRelayed.addAndGet(bytes);
                        }
                    } catch (RuntimeException e) {
                        // A malformed backend packet must not terminate this handler.
                        incrementParseErrors();
                        log.warn("Malformed packet from backend for {}: {}",
                                EmuUtil.formatSocketAddress(clientSocketAddress), e.getMessage(),
                                e);
                    }
                }
            } catch (Exception e) {
                if (!stopFlag && !closed) {
                    log.info("ClientHandler thread for {} caught exception: {}",
                            EmuUtil.formatSocketAddress(clientSocketAddress), e.getMessage(), e);
                }
            } finally {
                try {
                    clientChannel.close();
                } catch (Exception e) {
                    log.debug("Error closing client channel", e);
                }

                clients.remove(clientSocketAddress);
            }

            log.debug("ClientHandler thread for {} exiting...",
                    EmuUtil.formatSocketAddress(clientSocketAddress));
        }
    }
}
