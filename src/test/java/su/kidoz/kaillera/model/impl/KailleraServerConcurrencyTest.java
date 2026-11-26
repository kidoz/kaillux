package su.kidoz.kaillera.model.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import su.kidoz.kaillera.master.StatsCollector;
import su.kidoz.kaillera.metrics.GameMetricsCollector;
import su.kidoz.kaillera.metrics.ServerMetrics;
import su.kidoz.kaillera.service.ServerPolicyServices;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.exception.ChatException;
import su.kidoz.kaillera.model.exception.CreateGameException;
import su.kidoz.kaillera.model.exception.FloodException;
import su.kidoz.kaillera.model.exception.GameChatException;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.event.DefaultEventDispatcher;
import su.kidoz.kaillera.model.event.ChatEvent;
import su.kidoz.kaillera.model.event.EventDispatcher;
import su.kidoz.kaillera.model.event.KailleraEvent;
import su.kidoz.kaillera.model.event.KailleraEventListener;
import su.kidoz.kaillera.release.KailleraServerReleaseInfo;
import su.kidoz.util.EmuLinkerExecutor;

import su.kidoz.kaillera.model.validation.LoginValidator;
import su.kidoz.kaillera.service.AnnouncementService;
import su.kidoz.kaillera.service.ChatModerationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Concurrency tests for KailleraServerImpl.
 *
 * <p>
 * Tests thread-safety of server operations under concurrent load.
 */
class KailleraServerConcurrencyTest {

    /** The access level every address has, until a test changes it. */
    private final TestAccessManager accessManager = new TestAccessManager();

    /** Create-game flood interval of the test server in seconds; 0 disables it. */
    private volatile int createGameFloodSeconds = 0;

    private KailleraServerImpl server;
    private EmuLinkerExecutor executor;
    private ExecutorService testExecutor;

    @BeforeEach
    void setUp() {
        executor = new EmuLinkerExecutor();
        testExecutor = Executors.newVirtualThreadPerTaskExecutor();

        ServerConfig serverConfig = createTestServerConfig();
        GameConfig gameConfig = createTestGameConfig();
        MasterListConfig masterListConfig = createTestMasterListConfig();

        // Create dependencies for server
        accessManager.accessLevel = AccessManager.ACCESS_NORMAL;
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
        ServerMetrics metrics = new ServerMetrics(new TestStatsCollector(),
                // createGame records a metric; without a collector it would fail.
                new GameMetricsCollector(new SimpleMeterRegistry(), gameManager));

        server = new KailleraServerImpl(infrastructure, configs, policyServices, metrics,
                new AutoFireDetectorFactoryImpl(), userManager, gameManager);

    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (testExecutor != null) {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void userIdsShouldBeUnique() throws Exception {
        int numConnections = 100;
        Set<Integer> userIds = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(numConnections);
        AtomicInteger duplicates = new AtomicInteger(0);

        for (int i = 0; i < numConnections; i++) {
            final int clientId = i;
            testExecutor.submit(() -> {
                try {
                    InetSocketAddress address = new InetSocketAddress("127.0.0.1",
                            10000 + clientId);
                    KailleraUser user = server.newConnection(address, "v086",
                            createTestDispatcher());

                    if (!userIds.add(user.getID())) {
                        duplicates.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(20, TimeUnit.SECONDS));
        assertEquals(0, duplicates.get(), "User IDs should be unique");
        assertEquals(numConnections, userIds.size(), "All user IDs should be unique");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void gameIdsShouldBeUnique() throws Exception {
        int numGames = 50;
        List<KailleraUser> users = new ArrayList<>();
        for (int i = 0; i < numGames; i++) {
            users.add(logInUser(i));
        }
        assertEquals(numGames, server.getNumUsers(), "every user must be logged in");

        Set<Integer> gameIds = ConcurrentHashMap.newKeySet();
        AtomicInteger failures = new AtomicInteger();
        // Releasing the threads together makes them contend for the game lock.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(numGames);

        for (KailleraUser user : users) {
            testExecutor.submit(() -> {
                try {
                    start.await();
                    KailleraGame game = user.createGame("TestROM" + user.getID() + ".zip");
                    gameIds.add(game.getID());
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertEquals(0, failures.get(), "every createGame must succeed");
        assertEquals(numGames, gameIds.size(), "Game IDs should be unique");
        assertEquals(numGames, server.getNumGames(), "every game must be registered");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void userCountShouldBeAccurate() throws Exception {
        int numUsers = 50;
        CountDownLatch connectLatch = new CountDownLatch(numUsers);
        AtomicInteger connected = new AtomicInteger(0);

        for (int i = 0; i < numUsers; i++) {
            final int clientId = i;
            testExecutor.submit(() -> {
                try {
                    InetSocketAddress address = new InetSocketAddress("127.0.0.1",
                            10000 + clientId);
                    server.newConnection(address, "v086", createTestDispatcher());
                    connected.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    connectLatch.countDown();
                }
            });
        }

        assertTrue(connectLatch.await(20, TimeUnit.SECONDS));
        assertEquals(connected.get(), server.getNumUsers(),
                "User count should match connected users");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void everyUserReceivesEveryChatUnderConcurrentSenders() throws Exception {
        int numUsers = 20;
        int messagesPerUser = 10;
        int expectedChats = numUsers * messagesPerUser;

        List<CountingListener> listeners = new ArrayList<>();
        List<KailleraUser> users = new ArrayList<>();
        for (int i = 0; i < numUsers; i++) {
            CountingListener listener = new CountingListener();
            listeners.add(listener);
            users.add(logInUser(i, listener));
        }
        assertEquals(numUsers, server.getNumUsers(), "every user must be logged in");

        // Each user thread drains its own event queue and counts the chat events.
        for (KailleraUser user : users) {
            testExecutor.execute((KailleraUserImpl) user);
        }

        CountDownLatch done = new CountDownLatch(numUsers);
        AtomicInteger rejectedChats = new AtomicInteger();
        for (KailleraUser user : users) {
            testExecutor.submit(() -> {
                try {
                    for (int j = 0; j < messagesPerUser; j++) {
                        user.chat("Message " + j);
                    }
                } catch (Exception e) {
                    rejectedChats.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(20, TimeUnit.SECONDS), "every sender must finish");
        assertEquals(0, rejectedChats.get(), "every chat must be accepted");

        long deadline = System.currentTimeMillis() + 10_000;
        for (CountingListener listener : listeners) {
            while (listener.chats.get() < expectedChats && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
        }
        for (CountingListener listener : listeners) {
            assertEquals(expectedChats, listener.chats.get(),
                    "each user must receive every chat message exactly once");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void aNormalUserCanCreateGamesWhileFloodControlIsOn() throws Exception {
        createGameFloodSeconds = 1;
        KailleraUser user = logInUser(0);

        // The flood check compares with the previous attempt, so the first attempt
        // must go through. Writing the timestamp before that check denied it.
        KailleraGame first = user.createGame("FirstROM");
        assertEquals(KailleraGame.STATUS_WAITING, first.getStatus());

        user.quitGame();

        // Every attempt starts a new interval, so a retry at once is flood.
        assertThrows(FloodException.class, () -> user.createGame("SecondROM"));

        Thread.sleep(1100);
        KailleraGame second = user.createGame("SecondROM");
        assertNotEquals(first.getID(), second.getID(), "a new game must be created");
    }

    @Test
    void aBannedSessionIsRefusedInsteadOfExempt() throws Exception {
        KailleraUser user = logInUser(0);
        user.createGame("BannedROM");

        // The ban applies at once; no maintenance pass has disconnected the user yet.
        accessManager.accessLevel = AccessManager.ACCESS_BANNED;

        // These paths used to exempt everything that was not ACCESS_NORMAL, which
        // left a banned session as free of the rules as an admin.
        assertThrows(ChatException.class, () -> user.chat("hello"));
        assertThrows(GameChatException.class, () -> user.gameChat("hello", 1));

        user.quitGame();
        assertThrows(CreateGameException.class, () -> user.createGame("AnotherROM"));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void quittingAnOwnerRemovesTheGame() throws Exception {
        KailleraUser owner = logInUser(1);
        KailleraGame game = owner.createGame("OwnerQuitROM");
        assertEquals(1, server.getNumGames());

        owner.quit("done");

        assertEquals(0, server.getNumGames(), "the owned room must not outlive its owner");
        assertTrue(server.getGames().stream()
                .noneMatch(candidate -> candidate.getID() == game.getID()));
    }

    // Helper classes

    /**
     * Opens a session and completes its login, so that the user is logged in.
     *
     * @param id
     *            chooses the user's name and port
     * @return the logged-in user
     */
    private KailleraUser logInUser(int id) throws Exception {
        return logInUser(id, new NoOpListener());
    }

    private KailleraUser logInUser(int id, KailleraEventListener listener) throws Exception {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 10000 + id);
        KailleraUser user = server.newConnection(address, "v086", createTestDispatcher(listener));
        // The login validators compare the address the client logs in from with the
        // address it handshook from.
        user.setSocketAddress(address);
        user.setName("User" + id);
        user.setClientType("TestClient");
        user.setConnectionType(KailleraUser.CONNECTION_TYPE_LAN);
        user.setPing(10);
        user.login();
        return user;
    }

    private static EventDispatcher createTestDispatcher() {
        return createTestDispatcher(new NoOpListener());
    }

    private static EventDispatcher createTestDispatcher(KailleraEventListener listener) {
        EventDispatcher dispatcher = new DefaultEventDispatcher();
        dispatcher.setListener(listener);
        return dispatcher;
    }

    private static final class NoOpListener implements KailleraEventListener {
        @Override
        public void actionPerformed(KailleraEvent event) {
        }

        @Override
        public void stop() {
        }
    }

    /** Counts the chat events one user is given. */
    private static final class CountingListener implements KailleraEventListener {
        private final AtomicInteger chats = new AtomicInteger();

        @Override
        public void actionPerformed(KailleraEvent event) {
            if (event instanceof ChatEvent) {
                chats.incrementAndGet();
            }
        }

        @Override
        public void stop() {
        }
    }

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
            public int getMaxUsersPerAddress() {
                return 0;
            }

            @Override
            public int getMaxConnecting() {
                return 0;
            }

            @Override
            public int getMaxConnectingPerAddress() {
                return 0;
            }

            @Override
            public int getHelloRatePerSecond() {
                return 0;
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
                return createGameFloodSeconds;
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

    /** Answers every address with the same access level. */
    private final class TestAccessManager implements AccessManager {
        private volatile int accessLevel = ACCESS_NORMAL;

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
            return accessLevel;
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
