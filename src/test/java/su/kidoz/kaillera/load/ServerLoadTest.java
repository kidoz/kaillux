package su.kidoz.kaillera.load;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import su.kidoz.config.GameConfig;
import su.kidoz.config.MasterListConfig;
import su.kidoz.config.ServerConfig;
import su.kidoz.config.ServerConfigs;
import su.kidoz.config.ServerInfrastructure;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.metrics.GameMetricsCollector;
import su.kidoz.kaillera.metrics.ServerMetrics;
import su.kidoz.kaillera.service.ServerPolicyServices;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.impl.AutoFireDetectorFactoryImpl;
import su.kidoz.kaillera.model.impl.KailleraServerImpl;
import su.kidoz.kaillera.release.KailleraServerReleaseInfo;
import su.kidoz.kaillera.master.StatsCollector;

import su.kidoz.kaillera.model.impl.GameManager;
import su.kidoz.kaillera.model.impl.UserManager;
import su.kidoz.kaillera.model.validation.LoginValidator;
import su.kidoz.kaillera.service.AnnouncementService;
import su.kidoz.kaillera.service.ChatModerationService;
import su.kidoz.util.EmuLinkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Load tests for KailleraServer with concurrent connections and operations.
 *
 * <p>
 * Run with: ./gradlew test -Dload.tests=true
 *
 * <p>
 * Tests include:
 * <ul>
 * <li>Concurrent connection handling</li>
 * <li>Game synchronization (create, join, start)</li>
 * <li>Chat message throughput</li>
 * <li>Rapid join/quit cycles</li>
 * <li>Latency injection simulation</li>
 * <li>Parameterized concurrency levels</li>
 * </ul>
 */
@Tag("load")
@EnabledIfSystemProperty(named = "load.tests", matches = "true")
class ServerLoadTest {

    private static final Logger log = LoggerFactory.getLogger(ServerLoadTest.class);

    private KailleraServerImpl server;
    private EmuLinkerExecutor executor;
    private ExecutorService testExecutor;
    private List<MockKailleraClient> clients;
    private LoadTestMetrics metrics;

    @BeforeEach
    void setUp() {
        executor = new EmuLinkerExecutor();
        testExecutor = Executors.newVirtualThreadPerTaskExecutor();
        clients = new ArrayList<>();
        metrics = new LoadTestMetrics();

        ServerConfig serverConfig = createTestServerConfig();
        GameConfig gameConfig = createTestGameConfig();
        MasterListConfig masterListConfig = createTestMasterListConfig();

        // Create dependencies for server
        TestAccessManager accessManager = new TestAccessManager();
        LoginValidator loginValidator = new LoginValidator(accessManager, serverConfig);
        ChatModerationService chatModerationService = new ChatModerationService(accessManager,
                serverConfig.getChatFloodTime(), serverConfig.getMaxChatLength());
        AnnouncementService announcementService = new AnnouncementService();
        UserManager userManager = new UserManager(serverConfig.getMaxUsers());
        GameManager gameManager = new GameManager(serverConfig.getMaxUsers());

        // Create record bundles
        ServerInfrastructure infrastructure = new ServerInfrastructure(executor, accessManager,
                new KailleraServerReleaseInfo());
        ServerConfigs configs = new ServerConfigs(serverConfig, gameConfig, masterListConfig);
        ServerPolicyServices policyServices = new ServerPolicyServices(loginValidator,
                chatModerationService, announcementService);
        ServerMetrics serverMetrics = new ServerMetrics(new TestStatsCollector(),
                // createGame records a metric; without a collector it would fail.
                new GameMetricsCollector(new SimpleMeterRegistry(), gameManager));

        server = new KailleraServerImpl(infrastructure, configs, policyServices, serverMetrics,
                new AutoFireDetectorFactoryImpl(), userManager, gameManager);
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Print metrics report
        log.info(metrics.generateReport());

        // Stop server first to release any blocking operations
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("Server stop failed: {}", e.getMessage());
            }
        }

        // Force disconnect clients
        for (MockKailleraClient client : clients) {
            try {
                client.quit();
            } catch (Exception ignored) {
            }
        }
        clients.clear();

        // Properly shutdown test executor with force if needed
        if (testExecutor != null) {
            testExecutor.shutdown();
            if (!testExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Forcing testExecutor shutdown");
                testExecutor.shutdownNow();
            }
        }

        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Should handle 100 concurrent connections")
    void shouldHandle100ConcurrentConnections() throws Exception {
        int numClients = LoadTestConfiguration.CONCURRENT_CLIENTS;
        CountDownLatch connectLatch = new CountDownLatch(numClients);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            testExecutor.submit(() -> {
                try {
                    MockKailleraClient client = new MockKailleraClient("LoadTestUser" + clientId,
                            clientId);
                    client.setMetrics(metrics);
                    synchronized (clients) {
                        clients.add(client);
                    }

                    if (client.connect(server,
                            LoadTestConfiguration.OPERATION_TIMEOUT.toMillis())) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // Connection may fail - tracked in metrics
                } finally {
                    connectLatch.countDown();
                }
            });
        }

        assertTrue(connectLatch.await(LoadTestConfiguration.TEST_TIMEOUT.toSeconds(),
                TimeUnit.SECONDS), "Connection timeout");

        double successRate = (successCount.get() * 100.0) / numClients;
        log.info("Connection success rate: {}% ({}/{})", String.format("%.1f", successRate),
                successCount.get(), numClients);

        assertTrue(successRate >= LoadTestConfiguration.MIN_SUCCESS_RATE,
                String.format("Success rate %.1f%% below minimum %.1f%%", successRate,
                        LoadTestConfiguration.MIN_SUCCESS_RATE));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 50, 100})
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Should handle variable concurrency levels")
    void shouldHandleVariableConcurrencyLevels(int numClients) throws Exception {
        CountDownLatch connectLatch = new CountDownLatch(numClients);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            testExecutor.submit(() -> {
                try {
                    MockKailleraClient client = new MockKailleraClient("ConcurrencyUser" + clientId,
                            clientId);
                    client.setMetrics(metrics);
                    synchronized (clients) {
                        clients.add(client);
                    }

                    if (client.connect(server,
                            LoadTestConfiguration.OPERATION_TIMEOUT.toMillis())) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    connectLatch.countDown();
                }
            });
        }

        assertTrue(connectLatch.await(LoadTestConfiguration.TEST_TIMEOUT.toSeconds(),
                TimeUnit.SECONDS), "Connection timeout");

        double successRate = (successCount.get() * 100.0) / numClients;
        log.info("Concurrency {} - Success rate: {}% ({}/{})", numClients,
                String.format("%.1f", successRate), successCount.get(), numClients);

        assertTrue(successRate >= LoadTestConfiguration.MIN_SUCCESS_RATE,
                String.format("Success rate %.1f%% below minimum %.1f%%", successRate,
                        LoadTestConfiguration.MIN_SUCCESS_RATE));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Should handle game synchronization flow")
    void shouldHandleGameSynchronization() throws Exception {
        int numGames = 10;
        int playersPerGame = LoadTestConfiguration.PLAYERS_PER_GAME;
        CountDownLatch gameLatch = new CountDownLatch(numGames);
        AtomicInteger gamesStarted = new AtomicInteger(0);

        for (int g = 0; g < numGames; g++) {
            final int gameId = g;
            testExecutor.submit(() -> {
                try {
                    List<MockKailleraClient> gamePlayers = new ArrayList<>();

                    // Create game owner
                    MockKailleraClient owner = new MockKailleraClient("Owner" + gameId,
                            gameId * 100);
                    owner.setMetrics(metrics);
                    synchronized (clients) {
                        clients.add(owner);
                    }

                    if (!owner.connect(server,
                            LoadTestConfiguration.OPERATION_TIMEOUT.toMillis())) {
                        return;
                    }
                    gamePlayers.add(owner);

                    KailleraGame game = owner.createGame("SyncTestROM" + gameId + ".zip");
                    if (game == null) {
                        return;
                    }

                    // Join additional players
                    for (int p = 1; p < playersPerGame; p++) {
                        MockKailleraClient player = new MockKailleraClient(
                                "Player" + gameId + "_" + p, gameId * 100 + p);
                        player.setMetrics(metrics);
                        synchronized (clients) {
                            clients.add(player);
                        }

                        if (player.connect(server,
                                LoadTestConfiguration.OPERATION_TIMEOUT.toMillis())) {
                            player.joinGame(game.getID(),
                                    LoadTestConfiguration.OPERATION_TIMEOUT.toMillis());
                            gamePlayers.add(player);
                        }
                    }

                    // Owner starts the game (transitions to SYNCHRONIZING state)
                    owner.startGame(LoadTestConfiguration.OPERATION_TIMEOUT.toMillis());

                    // All players ready (must be after startGame)
                    for (MockKailleraClient player : gamePlayers) {
                        player.playerReady();
                    }

                    gamesStarted.incrementAndGet();

                } catch (Exception e) {
                    log.debug("Game {} failed: {}", gameId, e.getMessage());
                } finally {
                    gameLatch.countDown();
                }
            });
        }

        assertTrue(
                gameLatch.await(LoadTestConfiguration.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                "Game synchronization timeout");

        log.info("Games started: {}/{}", gamesStarted.get(), numGames);
        assertTrue(gamesStarted.get() > 0, "At least one game should start successfully");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Should handle concurrent chat messages with metrics")
    void shouldHandleConcurrentChatMessages() throws Exception {
        int numClients = 50;
        int messagesPerClient = LoadTestConfiguration.MESSAGES_PER_CLIENT;
        CountDownLatch chatLatch = new CountDownLatch(numClients * messagesPerClient);
        AtomicInteger messagesSent = new AtomicInteger(0);

        // Connect all clients first
        for (int i = 0; i < numClients; i++) {
            MockKailleraClient client = new MockKailleraClient("ChatUser" + i, i);
            client.setMetrics(metrics);
            clients.add(client);
            try {
                client.connect(server, LoadTestConfiguration.OPERATION_TIMEOUT.toMillis());
            } catch (Exception ignored) {
            }
        }

        // Send messages concurrently
        for (MockKailleraClient client : clients) {
            testExecutor.submit(() -> {
                for (int j = 0; j < messagesPerClient; j++) {
                    try {
                        client.chat("Message " + j + " from " + client.getName());
                        messagesSent.incrementAndGet();
                        Thread.sleep(LoadTestConfiguration.OPERATION_DELAY_MS);
                    } catch (Exception ignored) {
                    } finally {
                        chatLatch.countDown();
                    }
                }
            });
        }

        assertTrue(
                chatLatch.await(LoadTestConfiguration.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                "Chat timeout");

        log.info("Messages sent: {}/{}", messagesSent.get(), numClients * messagesPerClient);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Should handle rapid join/quit cycles")
    void shouldHandleRapidJoinQuitCycles() throws Exception {
        int cycles = 50;
        CountDownLatch cycleLatch = new CountDownLatch(cycles);
        AtomicInteger successfulCycles = new AtomicInteger(0);

        for (int i = 0; i < cycles; i++) {
            final int cycleId = i;
            testExecutor.submit(() -> {
                try {
                    MockKailleraClient client = new MockKailleraClient("CycleUser" + cycleId,
                            cycleId);
                    client.setMetrics(metrics);

                    if (client.connect(server,
                            LoadTestConfiguration.OPERATION_TIMEOUT.toMillis())) {
                        Thread.sleep(LoadTestConfiguration.OPERATION_DELAY_MS);
                        client.quit();
                        successfulCycles.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    cycleLatch.countDown();
                }
            });
        }

        assertTrue(
                cycleLatch.await(LoadTestConfiguration.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                "Cycle timeout");

        double successRate = (successfulCycles.get() * 100.0) / cycles;
        log.info("Join/quit cycles: {}/{} ({}%)", successfulCycles.get(), cycles,
                String.format("%.1f", successRate));

        assertTrue(successRate >= LoadTestConfiguration.MIN_SUCCESS_RATE,
                String.format("Success rate %.1f%% below minimum %.1f%%", successRate,
                        LoadTestConfiguration.MIN_SUCCESS_RATE));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("Should handle concurrent game creation")
    void shouldHandleConcurrentGameCreation() throws Exception {
        int numGames = 30;
        CountDownLatch gameLatch = new CountDownLatch(numGames);
        AtomicInteger gamesCreated = new AtomicInteger(0);

        // Connect all clients first
        for (int i = 0; i < numGames; i++) {
            MockKailleraClient client = new MockKailleraClient("GameCreator" + i, i);
            client.setMetrics(metrics);
            clients.add(client);
            try {
                client.connect(server, LoadTestConfiguration.OPERATION_TIMEOUT.toMillis());
            } catch (Exception ignored) {
            }
        }

        // Create games concurrently
        for (int i = 0; i < clients.size(); i++) {
            MockKailleraClient client = clients.get(i);
            final int gameNum = i;
            testExecutor.submit(() -> {
                try {
                    if (client.createGame("TestROM" + gameNum + ".zip") != null) {
                        gamesCreated.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    gameLatch.countDown();
                }
            });
        }

        assertTrue(
                gameLatch.await(LoadTestConfiguration.TEST_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                "Game creation timeout");

        log.info("Games created: {}/{}", gamesCreated.get(), numGames);
        assertTrue(gamesCreated.get() > 0, "At least one game should be created");
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("Should handle connections with simulated latency")
    void shouldHandleConnectionsWithLatency() throws Exception {
        int numClients = 30;
        CountDownLatch connectLatch = new CountDownLatch(numClients);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < numClients; i++) {
            final int clientId = i;
            testExecutor.submit(() -> {
                try {
                    MockKailleraClient client = new MockKailleraClient("LatencyUser" + clientId,
                            clientId);
                    client.setMetrics(metrics);
                    client.setInjectedLatency(LoadTestConfiguration.INJECTED_LATENCY_MS);
                    synchronized (clients) {
                        clients.add(client);
                    }

                    // Use longer timeout due to injected latency
                    if (client.connect(server,
                            LoadTestConfiguration.OPERATION_TIMEOUT.toMillis() * 2)) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    connectLatch.countDown();
                }
            });
        }

        assertTrue(connectLatch.await(LoadTestConfiguration.TEST_TIMEOUT.toSeconds() * 2,
                TimeUnit.SECONDS), "Connection timeout with latency");

        double successRate = (successCount.get() * 100.0) / numClients;
        log.info("Latency test - Success rate: {}% ({}/{}) with {}ms latency",
                String.format("%.1f", successRate), successCount.get(), numClients,
                LoadTestConfiguration.INJECTED_LATENCY_MS);

        // Lower threshold for latency tests
        assertTrue(successRate >= 80.0, String
                .format("Success rate %.1f%% below minimum 80%% for latency test", successRate));
    }

    // Test configuration helpers

    private ServerConfig createTestServerConfig() {
        return new ServerConfig() {
            @Override
            public int getMaxPing() {
                return 1000;
            }

            @Override
            public int getMaxUsers() {
                return 200;
            }

            @Override
            public int getMaxGames() {
                return 100;
            }

            @Override
            public int getKeepAliveTimeout() {
                return 60000;
            }

            @Override
            public int getIdleTimeout() {
                return 60000;
            }

            @Override
            public int getChatFloodTime() {
                return 0;
            }

            @Override
            public int getCreateGameFloodTime() {
                return 0;
            }

            @Override
            public boolean isAllowSinglePlayer() {
                return true;
            }

            @Override
            public boolean isAllowMultipleConnections() {
                return true;
            }

            @Override
            public int getMaxUserNameLength() {
                return 31;
            }

            @Override
            public int getMaxChatLength() {
                return 127;
            }

            @Override
            public int getMaxGameNameLength() {
                return 127;
            }

            @Override
            public int getMaxQuitMessageLength() {
                return 127;
            }

            @Override
            public int getMaxClientNameLength() {
                return 127;
            }
        };
    }

    private GameConfig createTestGameConfig() {
        return new GameConfig() {
            @Override
            public int getBufferSize() {
                return 100;
            }

            @Override
            public int getTimeoutMillis() {
                return 5000;
            }

            @Override
            public int getDesynchTimeouts() {
                return 4;
            }

            @Override
            public int getDefaultAutoFireSensitivity() {
                return 0;
            }
        };
    }

    private MasterListConfig createTestMasterListConfig() {
        MasterListConfig config = new MasterListConfig();
        config.setTouchKaillera(false);
        config.setTouchEmulinker(false);
        return config;
    }

    private static final class TestAccessManager implements AccessManager {
        @Override
        public boolean isAddressAllowed(InetAddress address) {
            return true;
        }

        @Override
        public boolean isSilenced(InetAddress address) {
            return false;
        }

        @Override
        public boolean isEmulatorAllowed(String emulator) {
            return true;
        }

        @Override
        public boolean isGameAllowed(String game) {
            return true;
        }

        @Override
        public int getAccess(InetAddress address) {
            return ACCESS_NORMAL;
        }

        @Override
        public String getAnnouncement(InetAddress address) {
            return null;
        }

        @Override
        public void addTempBan(String pattern, int minutes) {
        }

        @Override
        public void addTempAdmin(String pattern, int minutes) {
        }

        @Override
        public void addSilenced(String pattern, int minutes) {
        }

        @Override
        public boolean clearTemp(InetAddress address) {
            return true;
        }
    }

    private static final class TestStatsCollector implements StatsCollector {
        @Override
        public void gameStarted(KailleraServer server, KailleraGame game) {
        }

        @Override
        public List<String> getStartedGamesList() {
            return List.of();
        }

        @Override
        public void clearStartedGamesList() {
        }
    }
}
