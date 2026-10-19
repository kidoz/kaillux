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
        V086Bundle inBundle;

        log.debug("-> {}", EmuUtil.dumpBuffer(receiveBuffer));

        try {
            // Parse without strict message number validation (-1 = accept any)
            inBundle = V086Bundle.parse(receiveBuffer, -1);
        } catch (ParseException e) {
            receiveBuffer.rewind();
            log.warn("Failed to parse from {}: {}", EmuUtil.formatSocketAddress(fromAddress),
                    EmuUtil.dumpBuffer(receiveBuffer), e);
            incrementParseErrors();
            return null;
        } catch (V086BundleFormatException e) {
            receiveBuffer.rewind();
            log.warn("Invalid bundle format from {}: {}", EmuUtil.formatSocketAddress(fromAddress),
                    EmuUtil.dumpBuffer(receiveBuffer), e);
            incrementParseErrors();
            return null;
        } catch (MessageFormatException e) {
            receiveBuffer.rewind();
            log.warn("Invalid message format from {}: {}", EmuUtil.formatSocketAddress(fromAddress),
                    EmuUtil.dumpBuffer(receiveBuffer), e);
            incrementParseErrors();
            return null;
        } catch (RuntimeException e) {
            // Malformed input can surface as an unchecked exception (for example a
            // BufferUnderflowException). Never let it escape into the relay's receive
            // loop, which would disable the whole port.
            receiveBuffer.rewind();
            log.warn("Malformed message from {}: {}", EmuUtil.formatSocketAddress(fromAddress),
                    EmuUtil.dumpBuffer(receiveBuffer), e);
            incrementParseErrors();
            return null;
        }

        log.debug("-> {}", inBundle);

        // Track message numbers for debugging
        V086Message[] inMessages = inBundle.getMessages();
        for (int i = 0; i < inBundle.getNumMessages(); i++) {
            int msgNum = inMessages[i].getNumber();
            if (msgNum > lastClientMessageNumber.get()) {
                lastClientMessageNumber.set(msgNum);
            }
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
        V086Bundle inBundle;

        log.debug("<- {}", EmuUtil.dumpBuffer(receiveBuffer));

        try {
            // Parse without strict message number validation (-1 = accept any)
            inBundle = V086Bundle.parse(receiveBuffer, -1);
        } catch (ParseException e) {
            receiveBuffer.rewind();
            log.warn("Failed to parse from backend: {}", EmuUtil.dumpBuffer(receiveBuffer), e);
            incrementParseErrors();
            return null;
        } catch (V086BundleFormatException e) {
            receiveBuffer.rewind();
            log.warn("Invalid bundle format from backend: {}", EmuUtil.dumpBuffer(receiveBuffer),
                    e);
            incrementParseErrors();
            return null;
        } catch (MessageFormatException e) {
            receiveBuffer.rewind();
            log.warn("Invalid message format from backend: {}", EmuUtil.dumpBuffer(receiveBuffer),
                    e);
            incrementParseErrors();
            return null;
        } catch (RuntimeException e) {
            receiveBuffer.rewind();
            log.warn("Malformed message from backend: {}", EmuUtil.dumpBuffer(receiveBuffer), e);
            incrementParseErrors();
            return null;
        }

        log.debug("<- {}", inBundle);

        // Track message numbers for debugging
        V086Message[] inMessages = inBundle.getMessages();
        for (int i = 0; i < inBundle.getNumMessages(); i++) {
            int msgNum = inMessages[i].getNumber();
            if (msgNum > lastServerMessageNumber.get()) {
                lastServerMessageNumber.set(msgNum);
            }
        }

        // Forward the original packet unchanged
        ByteBuffer sendBuffer = ByteBuffer.allocate(receiveBuffer.limit());
        receiveBuffer.rewind();
        sendBuffer.put(receiveBuffer);
        sendBuffer.flip();

        return sendBuffer;
    }
}
