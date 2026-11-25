package su.kidoz.kaillera.relay;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.kidoz.config.RelayConfig;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage_HELLO;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage_HELLOD00D;
import su.kidoz.kaillera.controller.connectcontroller.protocol.ConnectMessage_TOO;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.net.UDPRelay;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.ThrottledLogger;

/**
 * Spring-managed Kaillera connection relay controller.
 *
 * <p>
 * Handles the initial connection handshake protocol on the main relay port.
 * When a client connects and receives a port assignment from the backend
 * server, this controller spawns a {@link V086RelayController} to handle the
 * game protocol traffic on the assigned port.
 *
 * <p>
 * Protocol flow:
 * <ol>
 * <li>Client sends HELLO to this relay</li>
 * <li>Relay forwards HELLO to backend server</li>
 * <li>Backend responds with HELLOD00D containing port number</li>
 * <li>Relay spawns V086RelayController for that port</li>
 * <li>Relay forwards HELLOD00D to client</li>
 * <li>Client connects to V086RelayController on assigned port</li>
 * </ol>
 */
public class KailleraRelayController extends UDPRelay {

    private static final Logger log = LoggerFactory.getLogger(KailleraRelayController.class);
    // Anyone can send to the relay port, so these may not be written once per
    // packet.
    private static final ThrottledLogger REJECTED_PACKET_LOG = new ThrottledLogger(log,
            "rejected relay packet");

    private final ExecutorService threadPool;
    private final ScheduledExecutorService scheduler;
    private final RelayConfig config;
    private final Map<Integer, V086RelayController> v086Relays = new ConcurrentHashMap<>();
    private final long idleTimeoutMs;

    private ScheduledFuture<?> cleanupTask;

    /**
     * Creates a new Kaillera relay controller.
     *
     * @param threadPool
     *            the executor service for handling connections
     * @param scheduler
     *            the scheduler for periodic cleanup tasks
     * @param config
     *            the relay configuration
     */
    public KailleraRelayController(ExecutorService threadPool, ScheduledExecutorService scheduler,
            RelayConfig config) {
        this(threadPool, scheduler, config, UDPRelay.SINGLE_PACKET_IDLE_TIMEOUT_MS,
                UDPRelay.DEFAULT_CLIENT_IDLE_TIMEOUT_MS);
    }

    /**
     * Creates a Kaillera relay controller with explicit client-flow idle timeouts.
     *
     * @param threadPool
     *            the executor service for handling connections
     * @param scheduler
     *            the scheduler for periodic cleanup tasks
     * @param config
     *            the relay configuration
     * @param singlePacketIdleTimeoutMs
     *            idle timeout for a flow whose client sent one datagram
     * @param clientIdleTimeoutMs
     *            idle timeout for every other flow
     */
    KailleraRelayController(ExecutorService threadPool, ScheduledExecutorService scheduler,
            RelayConfig config, long singlePacketIdleTimeoutMs, long clientIdleTimeoutMs) {
        super(threadPool, config.getListenPort(),
                new InetSocketAddress(config.getBackendHost(), config.getBackendPort()),
                config.getMaxConnections(), config.getBufferSize(), singlePacketIdleTimeoutMs,
                clientIdleTimeoutMs);
        this.threadPool = threadPool;
        this.scheduler = scheduler;
        this.config = config;
        this.idleTimeoutMs = config.getIdleTimeoutSeconds() * 1000L;
    }

    /**
     * Returns all active V086 relay controllers.
     */
    public Map<Integer, V086RelayController> getV086Relays() {
        return v086Relays;
    }

    @Override
    public String toString() {
        return "KailleraRelayController(port=" + getListenPort() + ", backend="
                + getServerSocketAddress() + ")";
    }

    @Override
    public void start() {
        super.start();

        // Start periodic cleanup task if idle timeout is enabled
        if (idleTimeoutMs > 0 && config.getCleanupIntervalSeconds() > 0) {
            cleanupTask = scheduler.scheduleAtFixedRate(this::cleanupIdleRelays,
                    config.getCleanupIntervalSeconds(), config.getCleanupIntervalSeconds(),
                    TimeUnit.SECONDS);
            log.info("Relay cleanup task started: interval={}s, idleTimeout={}s",
                    config.getCleanupIntervalSeconds(), config.getIdleTimeoutSeconds());
        }
    }

    @Override
    public void stop() {
        // Cancel cleanup task first
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
            cleanupTask = null;
        }

        // Stop all spawned V086 relays
        for (V086RelayController relay : v086Relays.values()) {
            try {
                relay.stop();
            } catch (Exception e) {
                log.warn("Error stopping V086 relay on port {}: {}", relay.getListenPort(),
                        e.getMessage());
            }
        }
        v086Relays.clear();

        super.stop();
    }

    /**
     * Cleans up V086 relay controllers that have been idle for longer than the
     * configured timeout.
     */
    private void cleanupIdleRelays() {
        if (idleTimeoutMs <= 0) {
            return;
        }

        Iterator<Map.Entry<Integer, V086RelayController>> iterator = v086Relays.entrySet()
                .iterator();

        while (iterator.hasNext()) {
            Map.Entry<Integer, V086RelayController> entry = iterator.next();
            V086RelayController relay = entry.getValue();

            if (relay.isIdle(idleTimeoutMs)) {
                log.info("Removing idle V086 relay on port {} (idle for >{}s)",
                        relay.getListenPort(), config.getIdleTimeoutSeconds());
                try {
                    relay.stop();
                } catch (Exception e) {
                    log.warn("Error stopping idle V086 relay on port {}: {}", relay.getListenPort(),
                            e.getMessage());
                }
                iterator.remove();
            }
        }
    }

    @Override
    protected ByteBuffer processClientToServer(ByteBuffer receiveBuffer,
            InetSocketAddress fromAddress, InetSocketAddress toAddress) {
        ConnectMessage inMessage;

        try {
            inMessage = ConnectMessage.parse(receiveBuffer);
        } catch (MessageFormatException e) {
            incrementParseErrors();
            REJECTED_PACKET_LOG.warn("Unrecognized message format from {}: {}",
                    EmuUtil.formatSocketAddress(fromAddress), e.toString());
            return null;
        }

        if (inMessage instanceof ConnectMessage_HELLO clientTypeMessage) {
            // The protocol string is client-chosen and unvalidated.
            log.debug("Client {} sent HELLO for protocol {}",
                    EmuUtil.formatSocketAddress(fromAddress),
                    InputValidator.sanitizeForLog(clientTypeMessage.getProtocol()));
        } else {
            // Only the server sends the other message types.
            REJECTED_PACKET_LOG.warn("Client {} sent unexpected message: {}",
                    EmuUtil.formatSocketAddress(fromAddress), inMessage);
            return null;
        }

        // Forward the original packet unchanged
        ByteBuffer sendBuffer = ByteBuffer.allocate(receiveBuffer.limit());
        receiveBuffer.rewind();
        sendBuffer.put(receiveBuffer);
        sendBuffer.flip();
        return sendBuffer;
    }

    @Override
    protected ByteBuffer processServerToClient(ByteBuffer receiveBuffer,
            InetSocketAddress fromAddress, InetSocketAddress toAddress) {
        ConnectMessage inMessage;

        try {
            inMessage = ConnectMessage.parse(receiveBuffer);
        } catch (MessageFormatException e) {
            incrementParseErrors();
            REJECTED_PACKET_LOG.warn("Unrecognized message format from backend: {}", e.toString());
            return null;
        }

        if (inMessage instanceof ConnectMessage_HELLOD00D portMsg) {
            int assignedPort = portMsg.getPort();
            log.info("Backend assigned port {} for client {}", assignedPort,
                    EmuUtil.formatSocketAddress(toAddress));

            // Spawn a V086 relay for this port if not already exists
            if (!v086Relays.containsKey(assignedPort)) {
                try {
                    InetSocketAddress backendAddress = new InetSocketAddress(
                            getServerSocketAddress().getAddress(), assignedPort);
                    V086RelayController v086Relay = new V086RelayController(threadPool,
                            assignedPort, backendAddress, config.getMaxConnections(),
                            config.getBufferSize());
                    v086Relay.start();
                    v086Relays.put(assignedPort, v086Relay);
                    log.info("Started V086 relay on port {} -> {}", assignedPort, backendAddress);
                } catch (Exception e) {
                    log.error("Failed to start V086 relay on port {}: {}", assignedPort,
                            e.getMessage(), e);
                    return null;
                }
            }
        } else if (inMessage instanceof ConnectMessage_TOO) {
            log.warn("Backend server is FULL for client {}",
                    EmuUtil.formatSocketAddress(toAddress));
        } else {
            log.warn("Backend sent unexpected message: {}", inMessage);
            return null;
        }

        // Forward the original packet unchanged
        ByteBuffer sendBuffer = ByteBuffer.allocate(receiveBuffer.limit());
        receiveBuffer.rewind();
        sendBuffer.put(receiveBuffer);
        sendBuffer.flip();
        return sendBuffer;
    }
}
