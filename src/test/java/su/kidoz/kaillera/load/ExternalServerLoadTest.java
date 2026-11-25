package su.kidoz.kaillera.load;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load tests for an external Kaillera server using UDP protocol.
 *
 * <p>
 * These tests connect to a running server via UDP and perform load testing.
 * Requires a server to be running before executing.
 *
 * <p>
 * Run with: ./gradlew test --tests "ExternalServerLoadTest" -Dload.tests=true
 * -Dload.external=true -Dkaillera.host=localhost -Dkaillera.port=27888
 */
@Tag("load")
@EnabledIfSystemProperty(named = "load.tests", matches = "true")
@EnabledIfSystemProperty(named = "load.external", matches = "true")
class ExternalServerLoadTest {

    private static final Logger log = LoggerFactory.getLogger(ExternalServerLoadTest.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 27888;
    private static final int DEFAULT_CLIENTS = 10;
    private static final long CONNECT_TIMEOUT_MS = 5000;
    private static final long LOGIN_TIMEOUT_MS = 5000;

    private String serverHost;
    private int serverPort;
    private volatile int numClients;

    private ExecutorService executor;
    private List<UdpKailleraClient> clients;

    @BeforeEach
    void setUp() {
        serverHost = System.getProperty("kaillera.host", DEFAULT_HOST);
        serverPort = Integer.getInteger("kaillera.port", DEFAULT_PORT);
        numClients = Integer.getInteger("kaillera.clients", DEFAULT_CLIENTS);

        executor = Executors.newVirtualThreadPerTaskExecutor();
        clients = new ArrayList<>();

        log.info("Load test configuration: host={}, port={}, clients={}", serverHost, serverPort,
                numClients);
    }

    @AfterEach
    void tearDown() throws Exception {
        log.info("Cleaning up {} clients...", clients.size());

        for (UdpKailleraClient client : clients) {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Error closing client: {}", e.getMessage());
            }
        }
        clients.clear();

        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Should connect multiple clients concurrently")
    @Timeout(60)
    void shouldConnectMultipleClients() throws Exception {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(numClients);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            executor.submit(() -> {
                try {
                    UdpKailleraClient client = new UdpKailleraClient(serverHost, serverPort,
                            "LoadUser" + clientId);
                    synchronized (clients) {
                        clients.add(client);
                    }

                    if (client.connect(CONNECT_TIMEOUT_MS)) {
                        log.info("Client {} connected on port {}", clientId,
                                client.getAssignedPort());
                        successCount.incrementAndGet();
                    } else {
                        log.warn("Client {} failed to connect", clientId);
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Client {} error: {}", clientId, e.getMessage());
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        log.info("Connection test completed in {}ms: {} success, {} failed", elapsed,
                successCount.get(), failCount.get());

        assertTrue(successCount.get() > 0, "At least one client should connect");
        assertTrue(successCount.get() >= numClients * 0.9,
                "At least 90% of clients should connect");
    }

    @Test
    @DisplayName("Should login multiple clients concurrently")
    @Timeout(120)
    void shouldLoginMultipleClients() throws Exception {
        AtomicInteger connectCount = new AtomicInteger(0);
        AtomicInteger loginCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(numClients);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            executor.submit(() -> {
                try {
                    UdpKailleraClient client = new UdpKailleraClient(serverHost, serverPort,
                            "LoadUser" + clientId);
                    synchronized (clients) {
                        clients.add(client);
                    }

                    if (client.connect(CONNECT_TIMEOUT_MS)) {
                        connectCount.incrementAndGet();
                        log.debug("Client {} connected, attempting login...", clientId);

                        if (client.login(LOGIN_TIMEOUT_MS)) {
                            loginCount.incrementAndGet();
                            log.info("Client {} logged in successfully", clientId);
                        } else {
                            log.warn("Client {} login timeout", clientId);
                            failCount.incrementAndGet();
                        }
                    } else {
                        log.warn("Client {} failed to connect", clientId);
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Client {} error: {}", clientId, e.getMessage(), e);
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });

            // Small delay between client connections to avoid overwhelming the server
            Thread.sleep(50);
        }

        latch.await(60, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        log.info("Login test completed in {}ms: {} connected, {} logged in, {} failed", elapsed,
                connectCount.get(), loginCount.get(), failCount.get());

        assertTrue(connectCount.get() > 0, "At least one client should connect");
        assertTrue(loginCount.get() > 0, "At least one client should login");
    }

    @Test
    @DisplayName("Should handle chat messages from multiple clients")
    @Timeout(120)
    void shouldHandleChatMessages() throws Exception {
        int chatClients = Math.min(numClients, 5); // Limit chat test to 5 clients
        AtomicInteger loginCount = new AtomicInteger(0);
        AtomicInteger chatCount = new AtomicInteger(0);
        CountDownLatch loginLatch = new CountDownLatch(chatClients);
        CountDownLatch chatLatch = new CountDownLatch(chatClients);

        // First, connect and login all clients
        for (int i = 0; i < chatClients; i++) {
            final int clientId = i;
            executor.submit(() -> {
                try {
                    UdpKailleraClient client = new UdpKailleraClient(serverHost, serverPort,
                            "ChatUser" + clientId);
                    synchronized (clients) {
                        clients.add(client);
                    }

                    if (client.connect(CONNECT_TIMEOUT_MS) && client.login(LOGIN_TIMEOUT_MS)) {
                        loginCount.incrementAndGet();
                        log.info("ChatUser{} logged in", clientId);
                    }
                } catch (Exception e) {
                    log.error("ChatUser{} login error: {}", clientId, e.getMessage());
                } finally {
                    loginLatch.countDown();
                }
            });
            Thread.sleep(100);
        }

        loginLatch.await(30, TimeUnit.SECONDS);
        log.info("{} clients logged in for chat test", loginCount.get());

        if (loginCount.get() == 0) {
            log.warn("No clients logged in, skipping chat test");
            return;
        }

        // Now send chat messages
        for (UdpKailleraClient client : clients) {
            if (client.isLoggedIn()) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < 5; j++) {
                            client.chat("Test message " + j + " from " + client.getClientName());
                            Thread.sleep(100);
                        }
                        chatCount.incrementAndGet();
                    } catch (Exception e) {
                        log.error("Chat error for {}: {}", client.getClientName(), e.getMessage());
                    } finally {
                        chatLatch.countDown();
                    }
                });
            } else {
                chatLatch.countDown();
            }
        }

        chatLatch.await(30, TimeUnit.SECONDS);
        log.info("Chat test completed: {} clients sent messages", chatCount.get());

        assertTrue(chatCount.get() > 0, "At least one client should send chat messages");
    }

    @Test
    @DisplayName("Should handle rapid connect/disconnect cycles")
    @Timeout(120)
    void shouldHandleRapidConnectDisconnect() throws Exception {
        int cycles = 5;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int cycle = 0; cycle < cycles; cycle++) {
            log.info("Starting cycle {}/{}", cycle + 1, cycles);
            CountDownLatch latch = new CountDownLatch(numClients);
            List<UdpKailleraClient> cycleClients = new ArrayList<>();

            for (int i = 0; i < numClients; i++) {
                final int clientId = i;
                executor.submit(() -> {
                    UdpKailleraClient client = null;
                    try {
                        client = new UdpKailleraClient(serverHost, serverPort,
                                "CycleUser" + clientId);
                        synchronized (cycleClients) {
                            cycleClients.add(client);
                        }

                        if (client.connect(CONNECT_TIMEOUT_MS)) {
                            // Must login before quit - server rejects quit from non-logged-in users
                            if (client.login(LOGIN_TIMEOUT_MS)) {
                                client.quit("Cycle test");
                                successCount.incrementAndGet();
                            } else {
                                failCount.incrementAndGet();
                            }
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                        log.debug("Cycle client {} error: {}", clientId, e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });

                // Small delay to reduce port exhaustion pressure
                Thread.sleep(50);
            }

            latch.await(30, TimeUnit.SECONDS);

            // Close any remaining clients
            for (UdpKailleraClient client : cycleClients) {
                try {
                    client.close();
                } catch (Exception ignored) {
                }
            }

            // Small delay between cycles
            Thread.sleep(500);
        }

        log.info("Rapid connect/disconnect test: {} success, {} failed over {} cycles",
                successCount.get(), failCount.get(), cycles);

        assertTrue(successCount.get() > 0, "At least some connections should succeed");
    }

    /**
     * Main method for running load test directly without JUnit.
     */
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        int clients = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_CLIENTS;

        System.out.println("=== Kaillera UDP Load Test ===");
        System.out.println("Server: " + host + ":" + port);
        System.out.println("Clients: " + clients);
        System.out.println();

        AtomicInteger connected = new AtomicInteger(0);
        AtomicInteger loggedIn = new AtomicInteger(0);
        List<UdpKailleraClient> clientList = new ArrayList<>();
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(clients);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < clients; i++) {
            final int id = i;
            exec.submit(() -> {
                try {
                    UdpKailleraClient client = new UdpKailleraClient(host, port, "LoadTest" + id);
                    synchronized (clientList) {
                        clientList.add(client);
                    }

                    if (client.connect(5000)) {
                        connected.incrementAndGet();
                        if (client.login(5000)) {
                            loggedIn.incrementAndGet();
                            System.out.println("Client " + id + " logged in");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Client " + id + " error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
            Thread.sleep(50);
        }

        latch.await(60, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        System.out.println();
        System.out.println("=== Results ===");
        System.out.println("Time: " + elapsed + "ms");
        System.out.println("Connected: " + connected.get() + "/" + clients);
        System.out.println("Logged in: " + loggedIn.get() + "/" + clients);
        System.out.println();

        // Keep clients connected for a moment
        System.out.println("Holding connections for 5 seconds...");
        Thread.sleep(5000);

        // Cleanup
        System.out.println("Disconnecting clients...");
        for (UdpKailleraClient client : clientList) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }

        exec.shutdown();
        exec.awaitTermination(10, TimeUnit.SECONDS);

        System.out.println("Done!");
    }
}
