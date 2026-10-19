package su.kidoz.kaillera.model.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.event.DefaultEventDispatcher;
import su.kidoz.kaillera.model.event.EventDispatcher;
import su.kidoz.kaillera.model.exception.GameDataException;
import su.kidoz.kaillera.model.exception.JoinGameException;
import su.kidoz.kaillera.model.exception.StartGameException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests for KailleraGameImpl, particularly the getPlayer() NPE fix.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KailleraGameImpl Tests")
class KailleraGameImplTest {

    private static final int BUFFER_SIZE = 1024;
    private static final int TIMEOUT_MILLIS = 1000;
    private static final int DESYNCH_TIMEOUTS = 4;

    @Mock
    private KailleraServerImpl server;

    private EventDispatcher eventDispatcher;

    private KailleraUserImpl owner;
    private KailleraGameImpl game;

    @BeforeEach
    void setUp() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 27888);
        eventDispatcher = new DefaultEventDispatcher();
        owner = new KailleraUserImpl(1, "v086", address, eventDispatcher, server);
        owner.setName("GameOwner");
        owner.setSocketAddress(address); // Set client socket address for game operations

        game = new KailleraGameImpl(1, "TestROM", owner, server, BUFFER_SIZE, TIMEOUT_MILLIS,
                DESYNCH_TIMEOUTS);
    }

    @Nested
    @DisplayName("getPlayer() NPE protection")
    class GetPlayerNPEProtection {

        @Test
        @DisplayName("should return null for playerNumber 0")
        void shouldReturnNullForPlayerNumberZero() {
            KailleraUser result = game.getPlayer(0);
            assertNull(result, "getPlayer(0) should return null");
        }

        @Test
        @DisplayName("should return null for negative playerNumber")
        void shouldReturnNullForNegativePlayerNumber() {
            KailleraUser result = game.getPlayer(-1);
            assertNull(result, "getPlayer(-1) should return null");

            result = game.getPlayer(-100);
            assertNull(result, "getPlayer(-100) should return null");
        }

        @Test
        @DisplayName("should return null for playerNumber exceeding player count")
        void shouldReturnNullForExceedingPlayerNumber() {
            // No players joined yet
            KailleraUser result = game.getPlayer(1);
            assertNull(result, "getPlayer(1) should return null when no players joined");

            result = game.getPlayer(100);
            assertNull(result, "getPlayer(100) should return null");
        }

        @Test
        @DisplayName("should not throw exception for invalid player numbers")
        void shouldNotThrowForInvalidPlayerNumbers() {
            // These should all complete without throwing
            assertDoesNotThrow(() -> game.getPlayer(-1));
            assertDoesNotThrow(() -> game.getPlayer(0));
            assertDoesNotThrow(() -> game.getPlayer(Integer.MIN_VALUE));
            assertDoesNotThrow(() -> game.getPlayer(Integer.MAX_VALUE));
        }
    }

    @Nested
    @DisplayName("Basic functionality")
    class BasicFunctionality {

        @Test
        @DisplayName("should return correct game ID")
        void shouldReturnGameId() {
            assertEquals(1, game.getID());
        }

        @Test
        @DisplayName("should return correct ROM name")
        void shouldReturnRomName() {
            assertEquals("TestROM", game.getRomName());
        }

        @Test
        @DisplayName("should return correct owner")
        void shouldReturnOwner() {
            assertEquals(owner, game.getOwner());
        }

        @Test
        @DisplayName("should start with waiting status")
        void shouldStartWithWaitingStatus() {
            assertEquals(0, game.getStatus()); // STATUS_WAITING = 0
        }

        @Test
        @DisplayName("should return 0 players initially")
        void shouldReturnZeroPlayersInitially() {
            assertEquals(0, game.getNumPlayers());
        }
    }

    @Nested
    @DisplayName("Game Lifecycle - Join")
    class GameLifecycleJoin {

        @Mock
        private AccessManager accessManager;

        @BeforeEach
        void setUpAccessManager() {
            when(server.getAccessManager()).thenReturn(accessManager);
            // Mock for both connect and socket address since join/start uses
            // getSocketAddress()
            when(accessManager.getAccess(owner.getConnectSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);
            when(accessManager.getAccess(owner.getSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);
        }

        @Test
        @DisplayName("owner should be able to join their own game")
        void ownerShouldJoinOwnGame() throws Exception {
            int playerNumber = game.join(owner);

            assertEquals(1, playerNumber);
            assertEquals(1, game.getNumPlayers());
            assertEquals(owner, game.getPlayer(1));
        }

        @Test
        @DisplayName("second player should join as player 2")
        void secondPlayerShouldJoinAsPlayer2() throws Exception {
            InetSocketAddress address2 = new InetSocketAddress("127.0.0.2", 27889);
            KailleraUserImpl player2 = new KailleraUserImpl(2, "v086", address2, eventDispatcher,
                    server);
            player2.setName("Player2");
            player2.setSocketAddress(address2);

            when(accessManager.getAccess(player2.getConnectSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);

            game.join(owner);
            int playerNumber = game.join(player2);

            assertEquals(2, playerNumber);
            assertEquals(2, game.getNumPlayers());
        }

        @Test
        @DisplayName("should not allow duplicate join")
        void shouldNotAllowDuplicateJoin() throws Exception {
            game.join(owner);

            assertThrows(JoinGameException.class, () -> game.join(owner));
        }

        @Test
        @DisplayName("should not allow joining game in progress for normal users")
        void shouldNotAllowJoiningGameInProgress() throws Exception {
            InetSocketAddress address2 = new InetSocketAddress("127.0.0.2", 27889);
            KailleraUserImpl player2 = new KailleraUserImpl(2, "v086", address2, eventDispatcher,
                    server);
            player2.setName("Player2");
            player2.setSocketAddress(address2);
            player2.setConnectionType(owner.getConnectionType());
            player2.setClientType(owner.getClientType());

            when(accessManager.getAccess(player2.getConnectSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);
            when(server.getAllowSinglePlayer()).thenReturn(true);

            // Set up game in synchronizing status
            game.join(owner);
            owner.setConnectionType((byte) 1);
            owner.setClientType("TestEmu");
            game.start(owner);

            // Now try to join - should fail
            assertThrows(JoinGameException.class, () -> game.join(player2));
        }
    }

    @Nested
    @DisplayName("Game Lifecycle - Start")
    class GameLifecycleStart {

        @Mock
        private AccessManager accessManager;

        @BeforeEach
        void setUpAccessManager() {
            when(server.getAccessManager()).thenReturn(accessManager);
            // Mock for both connect and socket address since start() uses
            // getSocketAddress()
            when(accessManager.getAccess(owner.getConnectSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);
            when(accessManager.getAccess(owner.getSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);
        }

        @Test
        @DisplayName("only owner can start the game")
        void onlyOwnerCanStart() throws Exception {
            InetSocketAddress address2 = new InetSocketAddress("127.0.0.2", 27889);
            KailleraUserImpl player2 = new KailleraUserImpl(2, "v086", address2, eventDispatcher,
                    server);
            player2.setName("Player2");
            player2.setSocketAddress(address2);

            when(accessManager.getAccess(player2.getConnectSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);
            when(server.getAllowSinglePlayer()).thenReturn(false);

            game.join(owner);
            game.join(player2);

            assertThrows(StartGameException.class, () -> game.start(player2));
        }

        @Test
        @DisplayName("starting game should change status to synchronizing")
        void startingShouldChangeStatusToSynchronizing() throws Exception {
            // Set owner connection info first
            owner.setConnectionType((byte) 1);
            owner.setClientType("TestEmu");

            InetSocketAddress address2 = new InetSocketAddress("127.0.0.2", 27889);
            KailleraUserImpl player2 = new KailleraUserImpl(2, "v086", address2, eventDispatcher,
                    server);
            player2.setName("Player2");
            player2.setSocketAddress(address2);
            player2.setConnectionType(owner.getConnectionType());
            player2.setClientType(owner.getClientType());

            when(accessManager.getAccess(player2.getConnectSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);
            when(accessManager.getAccess(player2.getSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);
            when(server.getAllowSinglePlayer()).thenReturn(false);

            game.join(owner);
            game.join(player2);
            game.start(owner);

            assertEquals(KailleraGame.STATUS_SYNCHRONIZING, game.getStatus());
        }

        @Test
        @DisplayName("cannot start already playing game")
        void cannotStartAlreadyPlayingGame() throws Exception {
            when(server.getAllowSinglePlayer()).thenReturn(true);

            game.join(owner);
            owner.setConnectionType((byte) 1);
            owner.setClientType("TestEmu");
            game.start(owner);

            assertThrows(StartGameException.class, () -> game.start(owner));
        }
    }

    @Nested
    @DisplayName("Game State Tracking")
    class GameStateTracking {

        @Test
        @DisplayName("synched should be false initially")
        void synchedShouldBeFalseInitially() {
            assertFalse(game.isSynched());
        }

        @Test
        @DisplayName("should return correct player number")
        void shouldReturnCorrectPlayerNumber() throws Exception {
            when(server.getAccessManager())
                    .thenReturn(org.mockito.Mockito.mock(AccessManager.class));
            when(server.getAccessManager().getAccess(owner.getConnectSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);

            game.join(owner);

            assertEquals(1, game.getPlayerNumber(owner));
        }

        @Test
        @DisplayName("should return -1 for player not in game")
        void shouldReturnMinusOneForPlayerNotInGame() {
            InetSocketAddress address2 = new InetSocketAddress("127.0.0.2", 27889);
            KailleraUserImpl player2 = new KailleraUserImpl(2, "v086", address2, eventDispatcher,
                    server);

            assertEquals(-1, game.getPlayerNumber(player2));
        }

        @Test
        @DisplayName("getPlayers should return defensive copy")
        void getPlayersShouldReturnDefensiveCopy() throws Exception {
            when(server.getAccessManager())
                    .thenReturn(org.mockito.Mockito.mock(AccessManager.class));
            when(server.getAccessManager().getAccess(owner.getConnectSocketAddress().getAddress()))
                    .thenReturn(AccessManager.ACCESS_NORMAL);

            game.join(owner);
            var players1 = game.getPlayers();
            var players2 = game.getPlayers();

            assertEquals(players1.size(), players2.size());
            assertNotNull(players1);
        }

        @Test
        @DisplayName("should provide detailed string representation")
        void shouldProvideDetailedString() {
            String detailed = game.toDetailedString();

            assertTrue(detailed.contains("TestROM"));
            assertTrue(detailed.contains("id=1"));
        }
    }

    @Nested
    @DisplayName("Game Data")
    class GameDataHandling {

        @Mock
        private AccessManager accessManager;

        private KailleraUserImpl player2;

        @BeforeEach
        void setUpPlayingGame() throws Exception {
            when(server.getAccessManager()).thenReturn(accessManager);
            when(accessManager.getAccess(any())).thenReturn(AccessManager.ACCESS_NORMAL);
            when(server.getAllowSinglePlayer()).thenReturn(false);

            owner.setConnectionType((byte) 1);
            owner.setClientType("TestEmu");

            // actionsPerMessage is captured from the owner at construction.
            game = new KailleraGameImpl(1, "TestROM", owner, server, BUFFER_SIZE, TIMEOUT_MILLIS,
                    DESYNCH_TIMEOUTS);

            InetSocketAddress address2 = new InetSocketAddress("127.0.0.2", 27889);
            player2 = new KailleraUserImpl(2, "v086", address2, eventDispatcher, server);
            player2.setName("Player2");
            player2.setSocketAddress(address2);
            player2.setConnectionType(owner.getConnectionType());
            player2.setClientType(owner.getClientType());

            game.join(owner);
            game.join(player2);
            game.start(owner);
            game.ready(owner, 1);
            game.ready(player2, 2);
        }

        @Test
        @DisplayName("concurrent addData from all players should not deadlock")
        void concurrentAddDataShouldComplete() throws Exception {
            byte[] ownerData = {1, 2, 3, 4};
            byte[] player2Data = {5, 6, 7, 8};

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> ownerFuture = executor
                        .submit(() -> assertDoesNotThrow(() -> game.addData(owner, 1, ownerData)));
                Future<?> player2Future = executor.submit(
                        () -> assertDoesNotThrow(() -> game.addData(player2, 2, player2Data)));

                assertDoesNotThrow(() -> ownerFuture.get(2, TimeUnit.SECONDS));
                assertDoesNotThrow(() -> player2Future.get(2, TimeUnit.SECONDS));
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("payload that is not a multiple of actions should be rejected")
        void unalignedPayloadShouldBeRejected() throws Exception {
            owner.setConnectionType((byte) 2);
            player2.setConnectionType((byte) 2);

            KailleraGameImpl game2 = new KailleraGameImpl(2, "TestROM", owner, server, BUFFER_SIZE,
                    TIMEOUT_MILLIS, DESYNCH_TIMEOUTS);
            game2.join(owner);
            game2.join(player2);
            game2.start(owner);
            game2.ready(owner, 1);
            game2.ready(player2, 2);

            // Two actions per message, so a three-byte payload cannot be framed.
            assertThrows(GameDataException.class,
                    () -> game2.addData(owner, 1, new byte[]{1, 2, 3}));
        }
    }
}
