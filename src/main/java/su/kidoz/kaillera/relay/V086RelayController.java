package su.kidoz.kaillera.relay;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.kaillera.controller.v086.protocol.V086Bundle;
import su.kidoz.kaillera.controller.v086.protocol.V086BundleFormatException;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.net.UDPRelay;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.ThrottledLogger;

/**
 * Spring-managed V086 protocol relay controller.
 *
 * <p>
 * Handles the Kaillera v0.86 game protocol traffic on a dynamically assigned
 * port. This controller is spawned by {@link KailleraRelayController} when a
 * client receives a port assignment from the backend server.
 *
 * <p>
 * The relay parses and validates V086Bundle messages but forwards them
 * unchanged to maintain protocol compatibility. Message sequence numbers are
 * tracked for debugging purposes.
 */
public class V086RelayController extends UDPRelay {

    private static final Logger log = LoggerFactory.getLogger(V086RelayController.class);

    // Either side can send bad bundles, and the client side is untrusted.
    private static final ThrottledLogger MALFORMED_CLIENT_LOG = new ThrottledLogger(log,
            "malformed bundle relayed from a client");
    private static final ThrottledLogger MALFORMED_BACKEND_LOG = new ThrottledLogger(log,
            "malformed bundle relayed from the backend");

    private final AtomicInteger lastServerMessageNumber = new AtomicInteger(-1);
    private final AtomicInteger lastClientMessageNumber = new AtomicInteger(-1);

    /**
     * Creates a new V086 relay controller.
     *
     * @param threadPool
     *            the executor service for handling connections
     * @param listenPort
     *            the port to listen on
     * @param serverSocketAddress
     *            the backend server address for this port
     * @param maxConnections
     *            maximum concurrent connections
     * @param bufferSize
     *            buffer size for UDP packets
     */
    public V086RelayController(ExecutorService threadPool, int listenPort,
            InetSocketAddress serverSocketAddress, int maxConnections, int bufferSize) {
        super(threadPool, listenPort, serverSocketAddress, maxConnections, bufferSize);
    }

    /**
     * Creates a V086 relay controller with explicit idle timeouts.
     *
     * @param threadPool
     *            the executor service for handling connections
     * @param listenPort
     *            the port to listen on
     * @param serverSocketAddress
     *            the backend server address for this port
     * @param maxConnections
     *            maximum concurrent connections
     * @param bufferSize
     *            buffer size for UDP packets
     * @param singlePacketIdleTimeoutMs
     *            idle timeout for a flow whose client sent one datagram
     * @param clientIdleTimeoutMs
     *            idle timeout for every other flow
     */
    V086RelayController(ExecutorService threadPool, int listenPort,
            InetSocketAddress serverSocketAddress, int maxConnections, int bufferSize,
            long singlePacketIdleTimeoutMs, long clientIdleTimeoutMs) {
        super(threadPool, listenPort, serverSocketAddress, maxConnections, bufferSize,
                singlePacketIdleTimeoutMs, clientIdleTimeoutMs);
    }

    /**
     * Returns the last message number received from the server.
     */
    public int getLastServerMessageNumber() {
        return lastServerMessageNumber.get();
    }

    /**
     * Returns the last message number received from the client.
     */
    public int getLastClientMessageNumber() {
        return lastClientMessageNumber.get();
    }

    @Override
    public String toString() {
        return "V086RelayController(port=" + getListenPort() + ", backend="
                + getServerSocketAddress() + ")";
    }

    @Override
    protected ByteBuffer processClientToServer(ByteBuffer receiveBuffer,
            InetSocketAddress fromAddress, InetSocketAddress toAddress) {
        V086Bundle inBundle = parse(receiveBuffer, true, fromAddress);
        if (inBundle == null) {
            return null;
        }
        trackMessageNumbers(inBundle, lastClientMessageNumber);
        return unchanged(receiveBuffer);
    }

    @Override
    protected ByteBuffer processServerToClient(ByteBuffer receiveBuffer,
            InetSocketAddress fromAddress, InetSocketAddress toAddress) {
        V086Bundle inBundle = parse(receiveBuffer, false, fromAddress);
        if (inBundle == null) {
            return null;
        }
        trackMessageNumbers(inBundle, lastServerMessageNumber);
        return unchanged(receiveBuffer);
    }

    /**
     * Parses a bundle, or returns null when it is malformed.
     *
     * @param receiveBuffer
     *            the received packet
     * @param fromClient
     *            true when the packet came from the client side
     * @param peer
     *            the address the packet came from
     * @return the parsed bundle, or null
     */
    private V086Bundle parse(ByteBuffer receiveBuffer, boolean fromClient, InetSocketAddress peer) {
        try {
            // Parse without strict message number validation (-1 = accept any)
            V086Bundle inBundle = V086Bundle.parse(receiveBuffer, -1);
            if (log.isDebugEnabled()) {
                log.debug("{} {}", fromClient ? "->" : "<-", inBundle);
            }
            return inBundle;
        } catch (ParseException | V086BundleFormatException | MessageFormatException e) {
            return reject(receiveBuffer, fromClient, peer, e);
        } catch (RuntimeException e) {
            // Malformed input can surface as an unchecked exception (for example a
            // BufferUnderflowException). Never let it escape into the relay's receive
            // loop, which would disable the whole port.
            return reject(receiveBuffer, fromClient, peer, e);
        }
    }

    private V086Bundle reject(ByteBuffer receiveBuffer, boolean fromClient, InetSocketAddress peer,
            Exception cause) {
        receiveBuffer.rewind();
        incrementParseErrors();
        String source = fromClient ? "client " + EmuUtil.formatSocketAddress(peer) : "backend";
        // The bytes are left out: a client picks them, and dumping them would multiply
        // the cost of a junk datagram.
        (fromClient ? MALFORMED_CLIENT_LOG : MALFORMED_BACKEND_LOG).warn(
                "Dropped a malformed bundle from {} ({} bytes): {}", source,
                receiveBuffer.remaining(), cause.toString());
        return null;
    }

    /**
     * Records the highest message number of the bundle that was just parsed.
     */
    private void trackMessageNumbers(V086Bundle inBundle, AtomicInteger last) {
        V086Message[] inMessages = inBundle.getMessages();
        for (int i = 0; i < inBundle.getNumMessages(); i++) {
            last.accumulateAndGet(inMessages[i].getNumber(), Math::max);
        }
    }

    /**
     * Copies the received packet for forwarding, unchanged.
     */
    private static ByteBuffer unchanged(ByteBuffer receiveBuffer) {
        ByteBuffer sendBuffer = ByteBuffer.allocate(receiveBuffer.limit());
        receiveBuffer.rewind();
        sendBuffer.put(receiveBuffer);
        sendBuffer.flip();
        return sendBuffer;
    }
}
