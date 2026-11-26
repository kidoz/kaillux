package su.kidoz.kaillera.controller.connectcontroller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import su.kidoz.config.ControllersConfig;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.controller.KailleraServerController;
import su.kidoz.net.BindException;
import su.kidoz.util.EmuLinkerExecutor;

/**
 * Tests for how ConnectController handles datagrams that arrive concurrently.
 *
 * <p>
 * The tests call the controller's own {@code handleReceived} from several
 * threads at once. The controller is real and bound to loopback; only its
 * outgoing sends are captured, so no datagram leaves the process.
 */
@DisplayName("ConnectController Thread Safety Tests")
class ConnectControllerThreadSafetyTest {

    private static final InetSocketAddress CLIENT = new InetSocketAddress("127.0.0.1", 20000);

    private static final int ASSIGNED_PORT = 27889;

    private static EmuLinkerExecutor executor;

    private static KailleraServerController protocolController;

    private static CapturingConnectController controller;

    @BeforeAll
    static void setUpController() throws Exception {
        executor = new EmuLinkerExecutor();
        protocolController = mock(KailleraServerController.class);
        when(protocolController.getClientTypes()).thenReturn(new String[]{"0.83"});
        when(protocolController.newConnection(any(), anyString())).thenReturn(ASSIGNED_PORT);

        controller = newController(protocolController, true);
    }

    @AfterAll
    static void tearDownController() {
        controller.stop();
        executor.shutdown();
    }

    @Test
    @DisplayName("every datagram is counted and answered under concurrent senders")
    void everyDatagramIsCountedAndAnsweredUnderConcurrentSenders() throws Exception {
        int senders = 8;
        int datagramsPerSender = 500;
        int requestsBefore = controller.getRequestCount();
        int repliesBefore = controller.replies.get();

        ExecutorService pool = Executors.newFixedThreadPool(senders);
        // Releasing the threads together makes them contend for the controller.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(senders);
        try {
            for (int t = 0; t < senders; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < datagramsPerSender; i++) {
                            controller.handleReceived(ping(), CLIENT);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(20, TimeUnit.SECONDS), "every sender must finish");
        } finally {
            pool.shutdownNow();
        }

        int expected = senders * datagramsPerSender;
        assertEquals(expected, controller.getRequestCount() - requestsBefore,
                "every datagram must be counted once");
        assertEquals(expected, controller.getPingCount() + controller.getPingRateLimitedCount(),
                "every PING must be counted exactly once, answered or limited");
        assertEquals(controller.getPingCount(), controller.replies.get() - repliesBefore,
                "every accepted PING must be answered with one PONG");
    }

    @Test
    @DisplayName("a valid HELLO is handed to the protocol controller and answered")
    void aValidHelloIsHandedToTheProtocolControllerAndAnswered() {
        int connectsBefore = controller.getConnectCount();
        int repliesBefore = controller.replies.get();

        controller.handleReceived(hello("0.83"), CLIENT);

        assertEquals(1, controller.getConnectCount() - connectsBefore);
        assertEquals(1, controller.replies.get() - repliesBefore);
    }

    @Test
    @DisplayName("junk is counted and not answered")
    void junkIsCountedAndNotAnswered() {
        int formatErrorsBefore = controller.getMessageFormatErrorCount();
        int repliesBefore = controller.replies.get();

        controller.handleReceived(ByteBuffer.wrap(new byte[]{(byte) 0xC3, 0x28, (byte) 0xFF}),
                CLIENT);

        assertEquals(1, controller.getMessageFormatErrorCount() - formatErrorsBefore);
        assertEquals(repliesBefore, controller.replies.get(), "junk must not be answered");
    }

    @Test
    @DisplayName("a HELLO for an unknown protocol is refused")
    void aHelloForAnUnknownProtocolIsRefused() {
        int protocolErrorsBefore = controller.getProtocolErrorCount();
        int repliesBefore = controller.replies.get();
        int connectsBefore = controller.getConnectCount();

        controller.handleReceived(hello("9.99"), CLIENT);

        assertEquals(1, controller.getProtocolErrorCount() - protocolErrorsBefore);
        assertEquals(1, controller.replies.get() - repliesBefore, "the client is told to go away");
        assertEquals(connectsBefore, controller.getConnectCount(), "no handshake is started");
    }

    @Test
    @DisplayName("a banned address is refused before any handshake work is done")
    void aBannedAddressIsRefusedBeforeAnyHandshakeWorkIsDone() {
        CapturingConnectController restricted = newController(protocolController, false);
        try {
            restricted.handleReceived(hello("0.83"), CLIENT);

            assertEquals(1, restricted.getDeniedOtherCount());
            assertEquals(1, restricted.replies.get(), "the client is told to go away");
            assertEquals(0, restricted.getConnectCount());
        } finally {
            restricted.stop();
        }
    }

    @Test
    @DisplayName("a handshake that throws is refused without stopping the listener")
    void aHandshakeThatThrowsIsRefusedWithoutStoppingTheListener() throws Exception {
        KailleraServerController failing = mock(KailleraServerController.class);
        when(failing.getClientTypes()).thenReturn(new String[]{"0.83"});
        when(failing.newConnection(any(), anyString()))
                .thenThrow(new IllegalStateException("handshake failure"));
        CapturingConnectController fragile = newController(failing, true);

        try {
            // The exception would end the receive loop of a real listener.
            assertDoesNotThrow(() -> fragile.handleReceived(hello("0.83"), CLIENT));
            assertEquals(1, fragile.getFailedToStartCount());
            assertEquals(1, fragile.replies.get(), "the client is told to go away");

            assertDoesNotThrow(() -> fragile.handleReceived(ping(), CLIENT));
            assertEquals(2, fragile.replies.get(), "the next datagram is still handled");
        } finally {
            fragile.stop();
        }
    }

    private static CapturingConnectController newController(KailleraServerController protocol,
            boolean addressAllowed) {
        AccessManager accessManager = mock(AccessManager.class);
        when(accessManager.isAddressAllowed(any())).thenReturn(addressAllowed);

        ControllersConfig config = new ControllersConfig();
        config.setBindAddresses(List.of("127.0.0.1"));
        // Port 0 lets each socket take a free port, so the tests never collide.
        config.getConnect().setPort(0);
        try {
            return new CapturingConnectController(executor,
                    new KailleraServerController[]{protocol}, accessManager, config);
        } catch (BindException e) {
            throw new IllegalStateException("Could not bind the test controller", e);
        }
    }

    private static ByteBuffer ping() {
        return ByteBuffer.wrap("PING\0".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static ByteBuffer hello(String protocol) {
        return ByteBuffer.wrap(("HELLO" + protocol + "\0").getBytes(StandardCharsets.ISO_8859_1));
    }

    /** The real controller, with its outgoing datagrams counted instead of sent. */
    private static final class CapturingConnectController extends ConnectController {
        private final AtomicInteger replies = new AtomicInteger();

        CapturingConnectController(EmuLinkerExecutor threadPool,
                KailleraServerController[] controllersArray, AccessManager accessManager,
                ControllersConfig config) throws BindException {
            super(threadPool, controllersArray, accessManager, config);
        }

        @Override
        protected void send(ByteBuffer buffer, InetSocketAddress toSocketAddress) {
            replies.incrementAndGet();
        }
    }
}
