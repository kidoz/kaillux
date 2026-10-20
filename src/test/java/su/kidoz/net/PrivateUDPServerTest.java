package su.kidoz.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

class PrivateUDPServerTest {

    @Test
    void dropsPacketsFromAnAddressOtherThanTheHandshake() throws Exception {
        InetAddress handshake = InetAddress.getByName("10.0.0.2");
        RecordingServer server = new RecordingServer(handshake);

        server.deliver(ByteBuffer.allocate(4),
                new InetSocketAddress(InetAddress.getByName("10.0.0.9"), 40000));

        assertEquals(0, server.received);
        assertNull(server.getRemoteSocketAddress());
    }

    @Test
    void pinsTheSourcePortFromTheHandshakeAddress() throws Exception {
        InetAddress handshake = InetAddress.getByName("10.0.0.2");
        RecordingServer server = new RecordingServer(handshake);
        InetSocketAddress first = new InetSocketAddress(handshake, 40000);
        InetSocketAddress otherPort = new InetSocketAddress(handshake, 40001);

        server.deliver(ByteBuffer.allocate(4), first);
        server.deliver(ByteBuffer.allocate(4), otherPort);
        server.deliver(ByteBuffer.allocate(4), first);

        assertEquals(2, server.received);
        assertEquals(first, server.getRemoteSocketAddress());
    }

    private static final class RecordingServer extends PrivateUDPServer {
        private int received;

        private RecordingServer(InetAddress remoteAddress) {
            super(false, remoteAddress);
        }

        private void deliver(ByteBuffer buffer, InetSocketAddress from) {
            handleReceived(buffer, from);
        }

        @Override
        protected void handleReceived(ByteBuffer buffer) {
            received++;
        }

        @Override
        protected ByteBuffer getBuffer() {
            return ByteBuffer.allocate(8);
        }

        @Override
        protected void releaseBuffer(ByteBuffer buffer) {
        }
    }
}
