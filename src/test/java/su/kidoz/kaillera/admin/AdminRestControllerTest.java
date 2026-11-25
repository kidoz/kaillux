package su.kidoz.kaillera.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import su.kidoz.config.ServerConfig;
import su.kidoz.kaillera.controller.KailleraServerController;
import su.kidoz.kaillera.controller.connectcontroller.ConnectController;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.release.KailleraServerReleaseInfo;
import su.kidoz.kaillera.service.GameService;
import su.kidoz.kaillera.service.UserService;
import su.kidoz.util.EmuLinkerExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit tests for AdminRestController using standalone MockMvc setup.
 *
 * <p>
 * Uses MockitoExtension for mocking dependencies without loading Spring
 * context.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminRestController Unit Tests")
class AdminRestControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    @Mock
    private GameService gameService;

    @Mock
    private KailleraServerReleaseInfo releaseInfo;

    @Mock
    private ConnectController connectController;

    @Mock
    private EmuLinkerExecutor executor;

    @BeforeEach
    void setUp() {
        AdminRestController controller = new AdminRestController(userService, gameService,
                releaseInfo, connectController, executor, Optional.empty(), Optional.empty(),
                new ServerConfig());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    @DisplayName("POST /api/admin/users/{userId}/kick")
    class KickUser {

        private KailleraUser kickable() {
            KailleraUser user = mock(KailleraUser.class);
            when(userService.findUser(7)).thenReturn(Optional.of(user));
            when(user.getName()).thenReturn("Player7");
            return user;
        }

        private org.springframework.test.web.servlet.ResultActions kick(String reasonJson)
                throws Exception {
            return mockMvc
                    .perform(post("/api/admin/users/7/kick").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":" + reasonJson + "}"));
        }

        @Test
        @DisplayName("should kick with the default reason when none is given")
        void shouldKickWithTheDefaultReasonWhenNoneIsGiven() throws Exception {
            KailleraUser user = kickable();

            kick("\"  \"").andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

            verify(userService).quit(user, "Kicked: Kicked by administrator");
        }

        @Test
        @DisplayName("should cut the reason to what the quit message can carry")
        void shouldCutTheReasonToWhatTheQuitMessageCanCarry() throws Exception {
            KailleraUser user = kickable();

            kick("\"" + "r".repeat(90) + "\"").andExpect(status().isOk()).andExpect(
                    jsonPath("$.message").value(org.hamcrest.Matchers.containsString("shortened")));

            // The server replaces a quit message longer than
            // server.max-quit-message-length (75) by its standard text, which used
            // to drop the reason without telling the administrator.
            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(userService).quit(eq(user), message.capture());
            assertEquals(75, message.getValue().length());
            assertTrue(message.getValue().startsWith("Kicked: rrrr"));
        }

        @Test
        @DisplayName("should keep a reason that fits as it is")
        void shouldKeepAReasonThatFitsAsItIs() throws Exception {
            KailleraUser user = kickable();

            kick("\"Excessive lag\"").andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("User Player7 has been kicked"));

            verify(userService).quit(user, "Kicked: Excessive lag");
        }

        @Test
        @DisplayName("should remove control characters from the reason")
        void shouldRemoveControlCharactersFromTheReason() throws Exception {
            KailleraUser user = kickable();

            kick("\"bye\\nFORGED LOG LINE\"").andExpect(status().isOk());

            verify(userService).quit(user, "Kicked: byeFORGED LOG LINE");
        }
    }

    @Nested
    @DisplayName("GET /api/admin/server-info")
    class GetServerInfo {

        @Test
        @DisplayName("should return server information with all fields")
        void shouldReturnServerInfo() throws Exception {
            // Setup mocks
            when(releaseInfo.getProductName()).thenReturn("EmuLinker");
            when(releaseInfo.getVersionString()).thenReturn("1.1.0");
            when(releaseInfo.getBuildNumber()).thenReturn(100);

            when(userService.getUserCount()).thenReturn(5);
            when(userService.getMaxUsers()).thenReturn(100);
            when(gameService.getGameCount()).thenReturn(2);
            when(gameService.getMaxGames()).thenReturn(50);

            when(connectController.getBindPort()).thenReturn(27888);
            when(connectController.isRunning()).thenReturn(true);
            when(connectController.getStartTime()).thenReturn(System.currentTimeMillis() - 60000);
            when(connectController.getRequestCount()).thenReturn(1000);
            when(connectController.getConnectCount()).thenReturn(500);
            when(connectController.getProtocolErrorCount()).thenReturn(10);
            when(connectController.getDeniedServerFullCount()).thenReturn(5);
            when(connectController.getDeniedOtherCount()).thenReturn(3);

            when(executor.getActiveCount()).thenReturn(10);
            when(executor.getPoolSize()).thenReturn(50);
            when(executor.getMaximumPoolSize()).thenReturn(Integer.MAX_VALUE);
            when(executor.getTaskCount()).thenReturn(1000L);

            // Execute and verify
            mockMvc.perform(get("/api/admin/server-info").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.serverName").value("EmuLinker"))
                    .andExpect(jsonPath("$.version").value("1.1.0"))
                    .andExpect(jsonPath("$.build").value(100))
                    .andExpect(jsonPath("$.running").value(true))
                    .andExpect(jsonPath("$.connectPort").value(27888))
                    .andExpect(jsonPath("$.userCount").value(5))
                    .andExpect(jsonPath("$.maxUsers").value(100))
                    .andExpect(jsonPath("$.gameCount").value(2))
                    .andExpect(jsonPath("$.maxGames").value(50))
                    .andExpect(jsonPath("$.stats.requestCount").value(1000))
                    .andExpect(jsonPath("$.stats.connectCount").value(500))
                    .andExpect(jsonPath("$.stats.protocolErrors").value(10))
                    .andExpect(jsonPath("$.stats.deniedFull").value(5))
                    .andExpect(jsonPath("$.stats.deniedOther").value(3))
                    .andExpect(jsonPath("$.threadPool.active").value(10))
                    .andExpect(jsonPath("$.threadPool.poolSize").value(50));
        }
    }

    @Nested
    @DisplayName("GET /api/admin/users")
    class GetUsers {

        @Test
        @DisplayName("should return empty list when no users")
        void shouldReturnEmptyListWhenNoUsers() throws Exception {
            doReturn(Collections.emptyList()).when(userService).getAllUsers();

            mockMvc.perform(get("/api/admin/users").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$").isArray()).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("should return user list with details")
        void shouldReturnUserListWithDetails() throws Exception {
            KailleraUser user1 = mock(KailleraUser.class);
            when(user1.getID()).thenReturn(1);
            when(user1.getName()).thenReturn("Player1");
            when(user1.getStatus()).thenReturn((int) KailleraUser.STATUS_IDLE);
            when(user1.getConnectionType()).thenReturn(KailleraUser.CONNECTION_TYPE_LAN);
            when(user1.getPing()).thenReturn(50);
            when(user1.getSocketAddress())
                    .thenReturn(new InetSocketAddress("192.168.1.100", 27889));
            when(user1.getConnectTime()).thenReturn(System.currentTimeMillis());

            KailleraUser user2 = mock(KailleraUser.class);
            when(user2.getID()).thenReturn(2);
            when(user2.getName()).thenReturn("Player2");
            when(user2.getStatus()).thenReturn((int) KailleraUser.STATUS_PLAYING);
            when(user2.getConnectionType()).thenReturn(KailleraUser.CONNECTION_TYPE_GOOD);
            when(user2.getPing()).thenReturn(100);
            when(user2.getSocketAddress()).thenReturn(new InetSocketAddress("10.0.0.50", 27890));
            when(user2.getConnectTime()).thenReturn(System.currentTimeMillis());

            doReturn(List.of(user1, user2)).when(userService).getAllUsers();

            mockMvc.perform(get("/api/admin/users").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].name").value("Player1"))
                    .andExpect(jsonPath("$[0].status").value("Idle"))
                    .andExpect(jsonPath("$[0].connectionType").value("Lan"))
                    .andExpect(jsonPath("$[0].ping").value(50))
                    .andExpect(jsonPath("$[0].address").value("192.168.1.100:27889"))
                    .andExpect(jsonPath("$[1].id").value(2))
                    .andExpect(jsonPath("$[1].name").value("Player2"))
                    .andExpect(jsonPath("$[1].status").value("Playing"))
                    .andExpect(jsonPath("$[1].connectionType").value("Good"));
        }
    }

    @Nested
    @DisplayName("GET /api/admin/games")
    class GetGames {

        @Test
        @DisplayName("should return empty list when no games")
        void shouldReturnEmptyListWhenNoGames() throws Exception {
            doReturn(Collections.emptyList()).when(gameService).getAllGames();

            mockMvc.perform(get("/api/admin/games").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$").isArray()).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("should return game list with details")
        void shouldReturnGameListWithDetails() throws Exception {
            KailleraUser owner = mock(KailleraUser.class);
            when(owner.getName()).thenReturn("GameMaster");

            KailleraGame game = mock(KailleraGame.class);
            when(game.getID()).thenReturn(1);
            when(game.getRomName()).thenReturn("Street Fighter II");
            when(game.getOwner()).thenReturn(owner);
            when(game.getStatus()).thenReturn((int) KailleraGame.STATUS_WAITING);
            when(game.getNumPlayers()).thenReturn(2);

            doReturn(Collections.singletonList(game)).when(gameService).getAllGames();

            mockMvc.perform(get("/api/admin/games").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(1))
                    .andExpect(jsonPath("$[0].rom").value("Street Fighter II"))
                    .andExpect(jsonPath("$[0].owner").value("GameMaster"))
                    .andExpect(jsonPath("$[0].status").value("Waiting"))
                    .andExpect(jsonPath("$[0].players").value(2));
        }
    }

    @Nested
    @DisplayName("GET /api/admin/controllers")
    class GetControllers {

        @Test
        @DisplayName("should return empty list when no controllers")
        void shouldReturnEmptyListWhenNoControllers() throws Exception {
            when(connectController.getControllers()).thenReturn(Collections.emptyList());

            mockMvc.perform(get("/api/admin/controllers").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$").isArray()).andExpect(jsonPath("$.length()").value(0));
        }

        @Test
        @DisplayName("should return controller list with details")
        void shouldReturnControllerListWithDetails() throws Exception {
            KailleraServerController controller = mock(KailleraServerController.class);
            when(controller.getVersion()).thenReturn("0.86");
            when(controller.getBufferSize()).thenReturn(4096);
            when(controller.getNumClients()).thenReturn(10);
            when(controller.getClientTypes()).thenReturn(new String[]{"v086"});

            when(connectController.getControllers())
                    .thenReturn(Collections.singletonList(controller));

            mockMvc.perform(get("/api/admin/controllers").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].version").value("0.86"))
                    .andExpect(jsonPath("$[0].bufferSize").value(4096))
                    .andExpect(jsonPath("$[0].numClients").value(10))
                    .andExpect(jsonPath("$[0].clientTypes[0]").value("v086"));
        }
    }
}
