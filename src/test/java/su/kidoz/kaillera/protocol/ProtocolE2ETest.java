package su.kidoz.kaillera.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.TimeUnit;

import su.kidoz.config.ControllersConfig;
import su.kidoz.config.GameConfig;
import su.kidoz.config.MasterListConfig;
import su.kidoz.config.ServerConfig;
import su.kidoz.config.ServerConfigs;
import su.kidoz.config.ServerInfrastructure;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.metrics.GameMetricsCollector;
import su.kidoz.kaillera.metrics.ServerMetrics;
import su.kidoz.kaillera.service.ServerPolicyServices;
import su.kidoz.kaillera.controller.KailleraServerController;
import su.kidoz.kaillera.controller.connectcontroller.ConnectController;
import su.kidoz.kaillera.controller.v086.V086Controller;
import su.kidoz.kaillera.controller.v086.command.ACKCommandAction;
import su.kidoz.kaillera.controller.v086.event.ACKEventRenderer;
import su.kidoz.kaillera.controller.v086.action.ActionRouter;
import su.kidoz.kaillera.controller.v086.command.AdminCommandAction;
import su.kidoz.kaillera.controller.v086.command.CachedGameDataAction;
import su.kidoz.kaillera.controller.v086.command.ChatCommandAction;
import su.kidoz.kaillera.controller.v086.event.ChatEventRenderer;
import su.kidoz.kaillera.controller.v086.event.CloseGameEventRenderer;
import su.kidoz.kaillera.controller.v086.command.CreateGameCommandAction;
import su.kidoz.kaillera.controller.v086.event.CreateGameEventRenderer;
import su.kidoz.kaillera.controller.v086.command.DropGameCommandAction;
import su.kidoz.kaillera.controller.v086.event.DropGameEventRenderer;
import su.kidoz.kaillera.controller.v086.command.GameChatCommandAction;
import su.kidoz.kaillera.controller.v086.event.GameChatEventRenderer;
import su.kidoz.kaillera.controller.v086.command.GameDataCommandAction;
import su.kidoz.kaillera.controller.v086.event.GameDataEventRenderer;
import su.kidoz.kaillera.controller.v086.event.GameDesynchEventRenderer;
import su.kidoz.kaillera.controller.v086.event.GameInfoEventRenderer;
import su.kidoz.kaillera.controller.v086.command.GameKickAction;
import su.kidoz.kaillera.controller.v086.command.GameOwnerCommandAction;
import su.kidoz.kaillera.controller.v086.event.GameStatusEventRenderer;
import su.kidoz.kaillera.controller.v086.event.GameTimeoutEventRenderer;
import su.kidoz.kaillera.controller.v086.event.InfoMessageEventRenderer;
import su.kidoz.kaillera.controller.v086.command.JoinGameCommandAction;
import su.kidoz.kaillera.controller.v086.event.JoinGameEventRenderer;
import su.kidoz.kaillera.controller.v086.command.KeepAliveAction;
import su.kidoz.kaillera.controller.v086.command.LoginCommandAction;
import su.kidoz.kaillera.controller.v086.event.LoginEventRenderer;
import su.kidoz.kaillera.controller.v086.event.LoginProgressEventRenderer;
import su.kidoz.kaillera.controller.v086.event.PlayerDesynchEventRenderer;
import su.kidoz.kaillera.controller.v086.command.QuitCommandAction;
import su.kidoz.kaillera.controller.v086.event.QuitEventRenderer;
import su.kidoz.kaillera.controller.v086.command.QuitGameCommandAction;
import su.kidoz.kaillera.controller.v086.event.QuitGameEventRenderer;
import su.kidoz.kaillera.controller.v086.command.StartGameCommandAction;
import su.kidoz.kaillera.controller.v086.event.StartGameEventRenderer;
import su.kidoz.kaillera.controller.v086.command.UserReadyCommandAction;
import su.kidoz.kaillera.controller.v086.event.UserReadyEventRenderer;
import su.kidoz.kaillera.load.UdpKailleraClient;
import su.kidoz.kaillera.master.StatsCollector;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.impl.AutoFireDetectorFactoryImpl;
import su.kidoz.kaillera.model.impl.KailleraServerImpl;
import su.kidoz.kaillera.release.KailleraServerReleaseInfo;

import su.kidoz.kaillera.model.impl.GameManager;
import su.kidoz.kaillera.model.impl.UserManager;
import su.kidoz.kaillera.model.validation.LoginValidator;
import su.kidoz.kaillera.service.AnnouncementService;
import su.kidoz.kaillera.service.ChatModerationService;
import su.kidoz.util.EmuLinkerExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end protocol tests that verify the Kaillera UDP protocol works
 * correctly.
 *
 * <p>
 * These tests start an embedded server with the full controller stack and use
 * {@link UdpKailleraClient} to test the actual UDP protocol communication.
 */
class ProtocolE2ETest {

    private static final Logger log = LoggerFactory.getLogger(ProtocolE2ETest.class);

    private static final int TEST_PORT = 27788; // Use non-standard port to avoid conflicts
    private static final long TIMEOUT_MS = 5000;

    private EmuLinkerExecutor executor;
    private KailleraServerImpl server;
    private V086Controller v086Controller;
    private ConnectController connectController;

    @BeforeEach
    void setUp() throws Exception {
        executor = new EmuLinkerExecutor();

        // Create configurations
        ServerConfig serverConfig = createServerConfig();
        GameConfig gameConfig = createGameConfig();
        MasterListConfig masterListConfig = createMasterListConfig();
        ControllersConfig controllersConfig = createControllersConfig();

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
        GameMetricsCollector gameMetricsCollector = new GameMetricsCollector(
                new SimpleMeterRegistry(), gameManager);
        ServerMetrics serverMetrics = new ServerMetrics(new TestStatsCollector(),
                gameMetricsCollector);

        // Create server
        server = new KailleraServerImpl(infrastructure, configs, policyServices, serverMetrics,
                new AutoFireDetectorFactoryImpl(), userManager, gameManager);
        server.start();

        // Create action router
        ActionRouter actionRouter = createActionRouter();

        // Create V086 controller
        v086Controller = new V086Controller(server, executor, controllersConfig, serverConfig,
                actionRouter);
        v086Controller.start();

        // Create connect controller
        KailleraServerController[] controllers = new KailleraServerController[]{v086Controller};
        connectController = new ConnectController(executor, controllers, new TestAccessManager(),
                controllersConfig);

        log.info("E2E test server started on port {}", TEST_PORT);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connectController != null) {
            connectController.stop();
        }
        if (v086Controller != null) {
            v086Controller.stop();
        }
        if (server != null) {
            server.stop();
        }
        if (executor != null) {
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        log.info("E2E test server stopped");
    }

    @Test
    @Timeout(30)
    @DisplayName("Should complete HELLO handshake and receive assigned port")
    void shouldCompleteHelloHandshake() throws Exception {
        UdpKailleraClient client = new UdpKailleraClient("localhost", TEST_PORT, "E2ETestUser1");

        try {
            boolean connected = client.connect(TIMEOUT_MS);

            assertTrue(connected, "Client should connect successfully");
            assertTrue(client.isConnected(), "Client should be in connected state");
            assertTrue(client.getAssignedPort() > 0, "Should receive assigned port");

            log.info("Client connected, assigned port: {}", client.getAssignedPort());
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Should complete full login flow")
    void shouldCompleteLoginFlow() throws Exception {
        UdpKailleraClient client = new UdpKailleraClient("localhost", TEST_PORT, "E2ETestUser2");

        try {
            // Connect
            boolean connected = client.connect(TIMEOUT_MS);
            assertTrue(connected, "Client should connect");

            // Login
            boolean loggedIn = client.login(TIMEOUT_MS);
            assertTrue(loggedIn, "Client should login successfully");
            assertTrue(client.isLoggedIn(), "Client should be in logged-in state");

            // Verify server state
            assertEquals(1, server.getNumUsers(), "Server should have 1 user");

            log.info("Client logged in successfully");
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Should send and receive chat messages")
    void shouldSendChatMessages() throws Exception {
        UdpKailleraClient client = new UdpKailleraClient("localhost", TEST_PORT, "E2EChatUser");

        try {
            // Connect and login
            assertTrue(client.connect(TIMEOUT_MS), "Client should connect");
            assertTrue(client.login(TIMEOUT_MS), "Client should login");

            // Send chat message (no exception = success)
            client.chat("Hello from E2E test!");

            // Small delay to let message process
            Thread.sleep(100);

            log.info("Chat message sent successfully");
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Should quit gracefully")
    void shouldQuitGracefully() throws Exception {
        UdpKailleraClient client = new UdpKailleraClient("localhost", TEST_PORT, "E2EQuitUser");

        try {
            // Connect and login
            assertTrue(client.connect(TIMEOUT_MS), "Client should connect");
            assertTrue(client.login(TIMEOUT_MS), "Client should login");
            assertEquals(1, server.getNumUsers(), "Server should have 1 user");

            // Quit
            client.quit("Goodbye!");

            // Wait for quit to process
            Thread.sleep(500);

            // Verify user was removed
            assertEquals(0, server.getNumUsers(), "Server should have 0 users after quit");

            log.info("Client quit gracefully");
        } finally {
            client.close();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Should handle multiple concurrent clients")
    void shouldHandleMultipleConcurrentClients() throws Exception {
        int numClients = 5;
        UdpKailleraClient[] clients = new UdpKailleraClient[numClients];

        try {
            // Connect all clients
            for (int i = 0; i < numClients; i++) {
                clients[i] = new UdpKailleraClient("localhost", TEST_PORT, "E2EMultiUser" + i);
                assertTrue(clients[i].connect(TIMEOUT_MS), "Client " + i + " should connect");
                assertTrue(clients[i].login(TIMEOUT_MS), "Client " + i + " should login");

                // Small delay between connections
                Thread.sleep(100);
            }

            // Verify all clients are connected
            assertEquals(numClients, server.getNumUsers(),
                    "Server should have " + numClients + " users");

            log.info("All {} clients connected and logged in", numClients);

            // Each client sends a chat message
            for (int i = 0; i < numClients; i++) {
                clients[i].chat("Message from client " + i);
            }

            Thread.sleep(200);

            // Disconnect all clients
            for (int i = 0; i < numClients; i++) {
                clients[i].quit("Bye from client " + i);
            }

            Thread.sleep(500);

            assertEquals(0, server.getNumUsers(), "Server should have 0 users after all quit");
        } finally {
            for (UdpKailleraClient client : clients) {
                if (client != null) {
                    try {
                        client.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("Should handle reconnection after disconnect")
    void shouldHandleReconnection() throws Exception {
        UdpKailleraClient client1 = new UdpKailleraClient("localhost", TEST_PORT,
                "E2EReconnectUser");

        try {
            // First connection
            assertTrue(client1.connect(TIMEOUT_MS), "First connect should succeed");
            assertTrue(client1.login(TIMEOUT_MS), "First login should succeed");
            assertEquals(1, server.getNumUsers(), "Server should have 1 user");

            // Disconnect
            client1.quit("First disconnect");
            Thread.sleep(500);
            assertEquals(0, server.getNumUsers(), "Server should have 0 users");
            client1.close();

            // Reconnect with new client
            UdpKailleraClient client2 = new UdpKailleraClient("localhost", TEST_PORT,
                    "E2EReconnectUser");
            try {
                assertTrue(client2.connect(TIMEOUT_MS), "Reconnect should succeed");
                assertTrue(client2.login(TIMEOUT_MS), "Reconnect login should succeed");
                assertEquals(1, server.getNumUsers(), "Server should have 1 user after reconnect");

                log.info("Reconnection successful");
            } finally {
                client2.close();
            }
        } finally {
            client1.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("Should play a complete two-player game over UDP")
    void shouldPlayCompleteGameOverUdp() throws Exception {
        UdpKailleraClient owner = new UdpKailleraClient("localhost", TEST_PORT, "E2EGameOwner");
        UdpKailleraClient player = new UdpKailleraClient("localhost", TEST_PORT, "E2EGamePlayer");

        try {
            assertTrue(owner.connect(TIMEOUT_MS), "Owner should connect");
            assertTrue(owner.login(TIMEOUT_MS), "Owner should login");
            assertTrue(owner.waitForUserId(TIMEOUT_MS), "Owner should learn its user ID");

            assertTrue(player.connect(TIMEOUT_MS), "Player should connect");
            assertTrue(player.login(TIMEOUT_MS), "Player should login");
            assertTrue(player.waitForUserId(TIMEOUT_MS), "Player should learn its user ID");

            int gameId = owner.createGame("E2EGameROM", TIMEOUT_MS);
            assertTrue(gameId > 0, "Owner should receive the created game ID");
            assertTrue(owner.isInGame(), "Owner should join the created game");

            assertTrue(player.joinGame(gameId, TIMEOUT_MS), "Player should join the game");
            assertTrue(player.isInGame(), "Player should be in the game");

            assertTrue(owner.startGame(TIMEOUT_MS), "Owner should start the game");
            assertTrue(player.waitForGameStarted(TIMEOUT_MS),
                    "Player should receive the start notification");
            assertEquals(1, owner.getPlayerNumber(), "Owner should be player 1");
            assertEquals(2, player.getPlayerNumber(), "Player should be player 2");

            owner.playerReady();
            player.playerReady();
            assertTrue(owner.waitForAllReady(TIMEOUT_MS), "Owner should see all players ready");
            assertTrue(player.waitForAllReady(TIMEOUT_MS), "Player should see all players ready");
            assertTrue(owner.isGameStarted(), "Game should be started");

            byte[] ownerInput = {0x01, 0x02, 0x03, 0x04};
            byte[] playerInput = {0x05, 0x06, 0x07, 0x08};
            byte[] expectedFrame = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

            owner.sendGameData(ownerInput);
            player.sendGameData(playerInput);
            assertTrue(owner.waitForGameDataCount(1, TIMEOUT_MS),
                    "Owner should receive assembled game data");
            assertTrue(player.waitForGameDataCount(1, TIMEOUT_MS),
                    "Player should receive assembled game data");
            assertArrayEquals(expectedFrame, owner.getReceivedGameData().get(0),
                    "Owner should receive both players' input");
            assertArrayEquals(expectedFrame, player.getReceivedGameData().get(0),
                    "Player should receive both players' input");

            // Key 0 is the first payload each client sent after the game started.
            owner.sendCachedGameData(0);
            player.sendCachedGameData(0);
            assertTrue(owner.waitForGameDataCount(2, TIMEOUT_MS),
                    "Owner should receive the cached frame");
            assertTrue(player.waitForGameDataCount(2, TIMEOUT_MS),
                    "Player should receive the cached frame");
            assertArrayEquals(expectedFrame, owner.getReceivedGameData().get(1),
                    "Cached frame should replay the same input");

            owner.gameChat("good luck");
            player.gameChat("gg");
            assertTrue(owner.waitForGameChat(2, TIMEOUT_MS),
                    "Owner should receive both game chat messages");
            assertTrue(player.waitForGameChat(2, TIMEOUT_MS),
                    "Player should receive both game chat messages");
            // The two clients' chats are processed by independent server threads, so
            // only the contents are deterministic, not their order.
            assertTrue(owner.getGameChatMessages().contains("E2EGameOwner: good luck"));
            assertTrue(owner.getGameChatMessages().contains("E2EGamePlayer: gg"));

            player.dropGame();
            assertTrue(owner.waitForDroppedPlayer(TIMEOUT_MS),
                    "Owner should be notified that the player dropped");
            assertEquals("E2EGamePlayer", owner.getLastDroppedPlayer());

            player.quitGame();
            assertTrue(player.waitForGameQuit(TIMEOUT_MS), "Player should leave the game");
            assertFalse(player.isInGame(), "Player should no longer be in the game");

            owner.quit("Game over");
            player.quit("Game over");
            waitForUserCount(0, TIMEOUT_MS);
            assertEquals(0, server.getNumUsers(), "Server should have no users");
        } finally {
            owner.close();
            player.close();
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("Should kick a player from a game over UDP")
    void shouldKickPlayerOverUdp() throws Exception {
        UdpKailleraClient owner = new UdpKailleraClient("localhost", TEST_PORT, "E2EKickOwner");
        UdpKailleraClient player = new UdpKailleraClient("localhost", TEST_PORT, "E2EKickPlayer");

        try {
            assertTrue(owner.connect(TIMEOUT_MS), "Owner should connect");
            assertTrue(owner.login(TIMEOUT_MS), "Owner should login");
            assertTrue(owner.waitForUserId(TIMEOUT_MS), "Owner should learn its user ID");

            assertTrue(player.connect(TIMEOUT_MS), "Player should connect");
            assertTrue(player.login(TIMEOUT_MS), "Player should login");
            assertTrue(player.waitForUserId(TIMEOUT_MS), "Player should learn its user ID");

            int gameId = owner.createGame("E2EKickROM", TIMEOUT_MS);
            assertTrue(gameId > 0, "Owner should receive the created game ID");
            assertTrue(player.joinGame(gameId, TIMEOUT_MS), "Player should join the game");

            owner.gameKick(player.getUserId());

            assertTrue(player.waitForGameQuit(TIMEOUT_MS), "Kicked player should leave the game");
            assertFalse(player.isInGame(), "Kicked player should no longer be in the game");
            assertTrue(owner.waitForPlayerQuit(player.getClientName(), TIMEOUT_MS),
                    "Owner should see the player quit the game");

            owner.quit("Kick test complete");
            player.quit("Kick test complete");
            waitForUserCount(0, TIMEOUT_MS);
            assertEquals(0, server.getNumUsers(), "Server should have no users");
        } finally {
            owner.close();
            player.close();
        }
    }

    private void waitForUserCount(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (server.getNumUsers() != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    // Configuration helpers

    private ServerConfig createServerConfig() {
        return new ServerConfig() {
            @Override
            public int getMaxPing() {
                return 1000;
            }

            @Override
            public int getMaxUsers() {
                return 100;
            }

            @Override
            public int getMaxGames() {
                return 50;
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

    private GameConfig createGameConfig() {
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

    private MasterListConfig createMasterListConfig() {
        MasterListConfig config = new MasterListConfig();
        config.setTouchKaillera(false);
        config.setTouchEmulinker(false);
        return config;
    }

    private ControllersConfig createControllersConfig() {
        ControllersConfig config = new ControllersConfig();

        ControllersConfig.Connect connectConfig = new ControllersConfig.Connect();
        connectConfig.setPort(TEST_PORT);
        connectConfig.setBufferSize(4096);
        config.setConnect(connectConfig);

        ControllersConfig.V086 v086Config = new ControllersConfig.V086();
        v086Config.setPortRangeStart(TEST_PORT + 1);
        v086Config.setBufferSize(4096);
        config.setV086(v086Config);

        config.setBindAddresses(List.of("0.0.0.0"));

        return config;
    }

    private ActionRouter createActionRouter() {
        AdminCommandAction adminCommandAction = new AdminCommandAction();
        GameOwnerCommandAction gameOwnerCommandAction = new GameOwnerCommandAction();

        return new ActionRouter(List.of(new ACKCommandAction(),
                new ChatCommandAction(adminCommandAction), new CreateGameCommandAction(),
                new DropGameCommandAction(), new GameChatCommandAction(gameOwnerCommandAction),
                new GameDataCommandAction(), new JoinGameCommandAction(), new LoginCommandAction(),
                new QuitCommandAction(), new QuitGameCommandAction(), new StartGameCommandAction(),
                new UserReadyCommandAction(), adminCommandAction, new CachedGameDataAction(),
                new GameKickAction(), gameOwnerCommandAction, new KeepAliveAction()),
                List.of(new ChatEventRenderer(), new CreateGameEventRenderer(),
                        new LoginEventRenderer(), new CloseGameEventRenderer(),
                        new QuitEventRenderer(), new GameStatusEventRenderer()),
                List.of(new JoinGameEventRenderer(), new QuitGameEventRenderer(),
                        new StartGameEventRenderer(), new GameChatEventRenderer(),
                        new UserReadyEventRenderer(), new GameDataEventRenderer(),
                        new DropGameEventRenderer(), new GameDesynchEventRenderer(),
                        new PlayerDesynchEventRenderer(), new GameInfoEventRenderer(),
                        new GameTimeoutEventRenderer()),
                List.of(new ACKEventRenderer(), new InfoMessageEventRenderer(),
                        new LoginProgressEventRenderer()));
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
