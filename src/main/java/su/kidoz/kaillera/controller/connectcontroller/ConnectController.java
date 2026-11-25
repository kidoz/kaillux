package su.kidoz.kaillera.controller.connectcontroller;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import su.kidoz.config.ControllersConfig;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.controller.KailleraServerController;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage_HELLO;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage_HELLOD00D;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage_PING;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage_PONG;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage_TOO;
import su.kidoz.kaillera.controller.messaging.ByteBufferMessage;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.model.exception.NewConnectionException;
import su.kidoz.kaillera.model.exception.ServerFullException;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.net.BindException;
import su.kidoz.net.MultiAddressUDPServer;
import su.kidoz.util.EmuLinkerExecutor;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.ThrottledLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

public class ConnectController extends MultiAddressUDPServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(ConnectController.class);

    /** Most bytes of a junk datagram the DEBUG log shows. */
    private static final int DUMP_LIMIT_BYTES = 64;

    // Anything on the internet can send to the connect port, including with a
    // spoofed source, so these events may not be written once per datagram.
    private static final ThrottledLogger INVALID_MESSAGE_LOG = new ThrottledLogger(log,
            "invalid connect message");
    private static final ThrottledLogger DENIED_CONNECTION_LOG = new ThrottledLogger(log,
            "denied connection");
    private static final ThrottledLogger FAILED_START_LOG = new ThrottledLogger(log,
            "failed to start a client handler");

    private final EmuLinkerExecutor threadPool;
    private final AccessManager accessManager;
    private final Map<String, KailleraServerController> controllersMap;

    private final int bufferSize;

    // One buffer per receive thread. A direct buffer per datagram on the public
    // connect port turns a flood into allocation pressure.
    private final ThreadLocal<ByteBuffer> receiveBuffer;

    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicInteger messageFormatErrorCount = new AtomicInteger(0);
    private final AtomicInteger protocolErrorCount = new AtomicInteger(0);
    private final AtomicInteger deniedServerFullCount = new AtomicInteger(0);
    private final AtomicInteger deniedOtherCount = new AtomicInteger(0);
    private final AtomicInteger failedToStartCount = new AtomicInteger(0);
    private final AtomicInteger connectedCount = new AtomicInteger(0);
    private final AtomicInteger pingCount = new AtomicInteger(0);

    public ConnectController(EmuLinkerExecutor threadPool,
            KailleraServerController[] controllersArray, AccessManager accessManager,
            ControllersConfig config) throws BindException {
        super(true);

        this.threadPool = threadPool;
        this.accessManager = accessManager;
        setExecutor(threadPool);

        int port = config.getConnect().getPort();
        int connectBufferSize = config.getConnect().getBufferSize();
        this.bufferSize = connectBufferSize;
        this.receiveBuffer = ThreadLocal
                .withInitial(() -> ByteBufferMessage.getBuffer(connectBufferSize));
        List<InetAddress> bindAddresses = config.getParsedBindAddresses();

        controllersMap = new HashMap<String, KailleraServerController>();
        for (KailleraServerController controller : controllersArray) {
            String[] clientTypes = controller.getClientTypes();
            for (int j = 0; j < clientTypes.length; j++) {
                log.debug("Mapping client type " + clientTypes[j] + " to " + controller);
                controllersMap.put(clientTypes[j], controller);
            }
        }

        super.bind(port, bindAddresses);

        log.info("Ready to accept connections on port {} (addresses: {})", port,
                bindAddresses.stream().map(InetAddress::getHostAddress).toList());
    }

    public KailleraServerController getController(String clientType) {
        return controllersMap.get(clientType);
    }

    public Collection<KailleraServerController> getControllers() {
        return controllersMap.values();
    }

    public long getStartTime() {
        return startTime.get();
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getRequestCount() {
        return requestCount.get();
    }

    public int getMessageFormatErrorCount() {
        return messageFormatErrorCount.get();
    }

    public int getProtocolErrorCount() {
        return protocolErrorCount.get();
    }

    public int getDeniedServerFullCount() {
        return deniedServerFullCount.get();
    }

    public int getDeniedOtherCount() {
        return deniedOtherCount.get();
    }

    public int getFailedToStartCount() {
        return failedToStartCount.get();
    }

    public int getConnectCount() {
        return connectedCount.get();
    }

    public int getPingCount() {
        return pingCount.get();
    }

    protected ByteBuffer getBuffer() {
        ByteBuffer buffer = receiveBuffer.get();
        buffer.clear();
        return buffer;
    }

    protected void releaseBuffer(ByteBuffer buffer) {
        // The buffer belongs to this thread and is reused by the next datagram.
    }

    public String toString() {
        if (getBindPort() > 0)
            return "ConnectController(" + getBindPort() + ")";
        else
            return "ConnectController(unbound)";
    }

    @Override
    public synchronized void start() {
        if (isRunning()) {
            log.debug(this + " start request ignored: already running!");
            return;
        }

        super.start();
        startTime.set(System.currentTimeMillis());
        log.debug(this + " Starting handlers (ThreadPool:" + threadPool.getActiveCount() + "/"
                + threadPool.getPoolSize() + ")");
        // run() starts all channel handlers using the executor
        this.run();
        log.info("ConnectController started on port " + getBindPort());
    }

    @Override
    public synchronized void stop() {
        super.stop();
        for (KailleraServerController controller : controllersMap.values())
            controller.stop();
        log.info("ConnectController stopped");
    }

    @Override
    public int getPhase() {
        // Phase 30: UDP listener, after protocol handler
        return 30;
    }

    /**
     * Handles one datagram from the public connect port.
     *
     * <p>
     * No monitor is taken here: the counters are atomic, the controller map never
     * changes after construction, and everything downstream synchronizes its own
     * state. Serializing handshakes would let one address family stall the other.
     */
    protected void handleReceived(ByteBuffer buffer, InetSocketAddress fromSocketAddress) {
        requestCount.incrementAndGet();

        ConnectMessage inMessage;

        try {
            inMessage = ConnectMessage.parse(buffer);
        } catch (MessageFormatException e) {
            messageFormatErrorCount.incrementAndGet();
            // A hex dump of attacker-chosen bytes multiplies the cost of a junk
            // datagram, so the dump is DEBUG-only and truncated there.
            INVALID_MESSAGE_LOG.warn("Received invalid message from {} ({} bytes)",
                    EmuUtil.formatSocketAddress(fromSocketAddress), buffer.limit());
            if (log.isDebugEnabled()) {
                buffer.rewind();
                buffer.limit(Math.min(buffer.limit(), DUMP_LIMIT_BYTES));
                log.debug("Invalid connect message from {}: {}",
                        EmuUtil.formatSocketAddress(fromSocketAddress), EmuUtil.dumpBuffer(buffer));
            }
            return;
        }

        // the message set of the ConnectController isn't really complex enough to
        // warrant a complicated request/action class
        // structure, so I'm going to handle it all in this class alone

        if (inMessage instanceof ConnectMessage_PING) {
            pingCount.incrementAndGet();
            log.debug("Ping from: " + EmuUtil.formatSocketAddress(fromSocketAddress));
            send(new ConnectMessage_PONG(), fromSocketAddress);
            return;
        }

        if (!(inMessage instanceof ConnectMessage_HELLO connectMessage)) {
            messageFormatErrorCount.incrementAndGet();
            // Only the server sends the other message types. A client that sends one is
            // confused or probing, which is not worth a warning per packet.
            log.debug("Received unexpected message type from {}: {}",
                    EmuUtil.formatSocketAddress(fromSocketAddress), inMessage);
            return;
        }

        // now we need to find the specific server this client is request to
        // connect to using the client type
        KailleraServerController protocolController = getController(connectMessage.getProtocol());
        if (protocolController == null) {
            protocolErrorCount.incrementAndGet();
            DENIED_CONNECTION_LOG.warn("Client requested an unhandled protocol from {}: {}",
                    EmuUtil.formatSocketAddress(fromSocketAddress),
                    InputValidator.sanitizeForLog(connectMessage.getProtocol()));
            send(new ConnectMessage_TOO(), fromSocketAddress);
            return;
        }

        if (!accessManager.isAddressAllowed(fromSocketAddress.getAddress())) {
            deniedOtherCount.incrementAndGet();
            DENIED_CONNECTION_LOG.warn("AccessManager denied connection from {}",
                    EmuUtil.formatSocketAddress(fromSocketAddress));
            send(new ConnectMessage_TOO(), fromSocketAddress);
        } else {
            int privatePort;

            try {
                privatePort = protocolController.newConnection(fromSocketAddress,
                        connectMessage.getProtocol());

                if (privatePort <= 0) {
                    failedToStartCount.incrementAndGet();
                    FAILED_START_LOG.error("{} failed to start for {}", protocolController,
                            EmuUtil.formatSocketAddress(fromSocketAddress));
                    send(new ConnectMessage_TOO(), fromSocketAddress);
                    return;
                }

                connectedCount.incrementAndGet();
                log.debug(protocolController + " allocated port " + privatePort + " to client from "
                        + fromSocketAddress.getAddress().getHostAddress());
                send(new ConnectMessage_HELLOD00D(privatePort), fromSocketAddress);
            } catch (ServerFullException e) {
                // Reached for every HELLO beyond an admission limit, so it stays DEBUG.
                deniedServerFullCount.incrementAndGet();
                log.debug("Sending server full response to "
                        + EmuUtil.formatSocketAddress(fromSocketAddress));
                send(new ConnectMessage_TOO(), fromSocketAddress);
            } catch (NewConnectionException e) {
                deniedOtherCount.incrementAndGet();
                DENIED_CONNECTION_LOG.warn("{} denied connection from {}: {}", protocolController,
                        EmuUtil.formatSocketAddress(fromSocketAddress), e.getMessage());
                send(new ConnectMessage_TOO(), fromSocketAddress);
            } catch (RuntimeException e) {
                // Escaping, this would end the receive loop and silence this address
                // family until restart.
                failedToStartCount.incrementAndGet();
                FAILED_START_LOG.error("{} failed while handling a connection request from {}",
                        protocolController, EmuUtil.formatSocketAddress(fromSocketAddress), e);
                send(new ConnectMessage_TOO(), fromSocketAddress);
            }
        }
    }

    protected void send(ConnectMessage outMessage, InetSocketAddress toSocketAddress) {
        send(outMessage.toBuffer(), toSocketAddress);
        outMessage.releaseBuffer();
    }
}
