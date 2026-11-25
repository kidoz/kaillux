package su.kidoz.kaillera.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import su.kidoz.config.RelayConfig;
import su.kidoz.config.ServerConfig;
import su.kidoz.kaillera.admin.dto.ActionResultDTO;
import su.kidoz.kaillera.admin.dto.ControllerDTO;
import su.kidoz.kaillera.admin.dto.EventMetricsDTO;
import su.kidoz.kaillera.admin.dto.GameDTO;
import su.kidoz.kaillera.admin.dto.KickUserRequest;
import su.kidoz.kaillera.admin.dto.RelayStatusDTO;
import su.kidoz.kaillera.admin.dto.ServerInfoDTO;
import su.kidoz.kaillera.admin.dto.UserDTO;
import su.kidoz.kaillera.controller.connectcontroller.ConnectController;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.kaillera.relay.KailleraRelayController;
import su.kidoz.kaillera.release.KailleraServerReleaseInfo;
import su.kidoz.kaillera.service.GameService;
import su.kidoz.kaillera.service.UserService;
import su.kidoz.util.EmuLinkerExecutor;

/**
 * REST controller for server administration operations.
 *
 * <p>
 * Provides endpoints for monitoring server status, managing connected users,
 * viewing active games, and performing administrative actions like kicking
 * users.
 */
@RestController
@RequestMapping({"/api/admin", "/api/v1/admin"})
@SecurityRequirement(name = "basicAuth")
@Tag(name = "Admin", description = "Server administration operations")
public class AdminRestController {

    private static final Logger log = LoggerFactory.getLogger(AdminRestController.class);

    /** Prefix put in front of the kick reason in the message the user sees. */
    static final String KICK_MESSAGE_PREFIX = "Kicked: ";

    /** Reason used when the administrator does not give one. */
    static final String DEFAULT_KICK_REASON = "Kicked by administrator";

    private final UserService userService;
    private final GameService gameService;
    private final KailleraServerReleaseInfo releaseInfo;
    private final ConnectController connectController;
    private final EmuLinkerExecutor executor;
    private final Optional<KailleraRelayController> relayController;
    private final Optional<RelayConfig> relayConfig;
    private final ServerConfig serverConfig;

    @Autowired
    public AdminRestController(UserService userService, GameService gameService,
            KailleraServerReleaseInfo releaseInfo, ConnectController connectController,
            EmuLinkerExecutor executor, Optional<KailleraRelayController> relayController,
            Optional<RelayConfig> relayConfig, ServerConfig serverConfig) {
        this.userService = userService;
        this.gameService = gameService;
        this.releaseInfo = releaseInfo;
        this.connectController = connectController;
        this.executor = executor;
        this.relayController = relayController;
        this.relayConfig = relayConfig;
        this.serverConfig = serverConfig;
    }

    @Operation(summary = "Get server information", description = "Returns comprehensive server status including version, uptime, "
            + "user/game counts, connection statistics, and thread pool metrics.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Server information retrieved", content = @Content(schema = @Schema(implementation = ServerInfoDTO.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid credentials", content = @Content)})
    @GetMapping("/server-info")
    public ServerInfoDTO getServerInfo() {
        ServerInfoDTO.StatsDTO stats = new ServerInfoDTO.StatsDTO(
                connectController.getRequestCount(), connectController.getConnectCount(),
                connectController.getProtocolErrorCount(),
                connectController.getDeniedServerFullCount(),
                connectController.getDeniedOtherCount());

        ServerInfoDTO.ThreadPoolDTO threadPool = new ServerInfoDTO.ThreadPoolDTO(
                executor.getActiveCount(), executor.getPoolSize(), executor.getMaximumPoolSize(),
                executor.getTaskCount());

        return new ServerInfoDTO(releaseInfo.getProductName(), releaseInfo.getVersionString(),
                releaseInfo.getBuildNumber(), connectController.isRunning(),
                connectController.getBindPort(),
                (System.currentTimeMillis() - connectController.getStartTime()) / 60000,
                userService.getUserCount(), userService.getMaxUsers(), gameService.getGameCount(),
                gameService.getMaxGames(), stats, threadPool);
    }

    @Operation(summary = "List all connected users", description = "Returns a list of all users currently connected to the server, "
            + "including their status, connection type, ping, and address.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "User list retrieved", content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserDTO.class)))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid credentials", content = @Content)})
    @GetMapping("/users")
    public List<UserDTO> getUsers() {
        return userService.getAllUsers().stream().map(user -> {
            // Null-safe socket address handling for users that haven't fully connected
            String address = "unknown";
            var socketAddr = user.getSocketAddress();
            if (socketAddr != null && socketAddr.getAddress() != null) {
                address = socketAddr.getAddress().getHostAddress() + ":" + socketAddr.getPort();
            }
            return new UserDTO(user.getID(), user.getName(),
                    KailleraUser.getStatusName(user.getStatus()),
                    KailleraUser.getConnectionTypeName(user.getConnectionType()), user.getPing(),
                    address, user.getConnectTime());
        }).collect(Collectors.toList());
    }

    @Operation(summary = "List all active games", description = "Returns a list of all games currently active on the server, "
            + "including ROM name, owner, status, and player count.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Game list retrieved", content = @Content(array = @ArraySchema(schema = @Schema(implementation = GameDTO.class)))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid credentials", content = @Content)})
    @GetMapping("/games")
    public List<GameDTO> getGames() {
        return gameService.getAllGames().stream()
                .map(game -> new GameDTO(game.getID(), game.getRomName(), game.getOwner().getName(),
                        KailleraGame.getStatusName(game.getStatus()), game.getNumPlayers()))
                .collect(Collectors.toList());
    }

    @Operation(summary = "List protocol controllers", description = "Returns information about active protocol controllers, "
            + "including version, buffer size, client count, and supported client types.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Controller list retrieved", content = @Content(array = @ArraySchema(schema = @Schema(implementation = ControllerDTO.class)))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid credentials", content = @Content)})
    @GetMapping("/controllers")
    public List<ControllerDTO> getControllers() {
        return connectController.getControllers().stream()
                .map(controller -> new ControllerDTO(controller.getVersion(),
                        controller.getBufferSize(), controller.getNumClients(),
                        Arrays.asList(controller.getClientTypes())))
                .collect(Collectors.toList());
    }

    /**
     * Returns event queue metrics for all connected users.
     *
     * @return list of event metrics for each user
     */
    @Operation(summary = "Get event metrics for all users", description = "Returns event queue metrics for all connected users, "
            + "including queue size, dropped events, and utilization percentage.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event metrics retrieved", content = @Content(array = @ArraySchema(schema = @Schema(implementation = EventMetricsDTO.class)))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid credentials", content = @Content)})
    @GetMapping("/event-metrics")
    public List<EventMetricsDTO> getAllEventMetrics() {
        return userService.getAllUsers().stream()
                .map(user -> EventMetricsDTO.of(user.getID(), user.getName(),
                        user.getEventQueueSize(), user.getDroppedEventsCount()))
                .collect(Collectors.toList());
    }

    /**
     * Returns event queue metrics for a specific user.
     *
     * @param userId
     *            the user ID
     * @return event metrics for the user, or 404 if not found
     */
    @Operation(summary = "Get event metrics for a specific user", description = "Returns event queue metrics for a single user by ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Event metrics retrieved", content = @Content(schema = @Schema(implementation = EventMetricsDTO.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid credentials", content = @Content),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content)})
    @GetMapping("/users/{userId}/events")
    public ResponseEntity<EventMetricsDTO> getUserEventMetrics(
            @Parameter(description = "User ID", example = "1") @PathVariable int userId) {
        var userOpt = userService.findUser(userId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        KailleraUser user = userOpt.get();
        return ResponseEntity.ok(EventMetricsDTO.of(user.getID(), user.getName(),
                user.getEventQueueSize(), user.getDroppedEventsCount()));
    }

    /**
     * Kicks a user from the server.
     *
     * @param userId
     *            the ID of the user to kick
     * @param request
     *            the kick request containing the reason
     * @return result indicating success or failure
     */
    @Operation(summary = "Kick a user from the server", description = "Disconnects a user from the server with an optional reason message.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "User kicked successfully", content = @Content(schema = @Schema(implementation = ActionResultDTO.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid credentials", content = @Content),
            @ApiResponse(responseCode = "404", description = "User not found", content = @Content),
            @ApiResponse(responseCode = "500", description = "Failed to kick user", content = @Content(schema = @Schema(implementation = ActionResultDTO.class)))})
    @PostMapping("/users/{userId}/kick")
    public ResponseEntity<ActionResultDTO> kickUser(
            @Parameter(description = "User ID to kick", example = "1") @PathVariable int userId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Kick request with optional reason", content = @Content(schema = @Schema(implementation = KickUserRequest.class))) @RequestBody KickUserRequest request) {
        var userOpt = userService.findUser(userId);

        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        KailleraUser user = userOpt.get();
        String requested = InputValidator.sanitize(request.reason());
        String reason = kickReason(requested);
        boolean shortened = requested != null && reason.length() < requested.length();

        try {
            userService.quit(user, KICK_MESSAGE_PREFIX + reason);
            log.info("Admin kicked user {} (ID: {}): {}", user.getName(), userId, reason);
            return ResponseEntity.ok(ActionResultDTO.ok("User " + user.getName()
                    + " has been kicked"
                    + (shortened
                            ? ". The reason was shortened to fit the message the user receives."
                            : "")));
        } catch (Exception e) {
            log.error("Failed to kick user {}: {}", userId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body(ActionResultDTO.error("Failed to kick user: " + e.getMessage()));
        }
    }

    /**
     * Brings the reason into the form the user can receive.
     *
     * <p>
     * The server replaces a quit message that is too long or unprintable with its
     * standard text, which would drop the reason without telling the administrator.
     * So the reason is cut here to what fits.
     *
     * @param cleaned
     *            the reason with its unprintable characters removed, possibly null
     * @return the reason to put after {@link #KICK_MESSAGE_PREFIX}
     */
    String kickReason(String cleaned) {
        if (cleaned == null || cleaned.isBlank()) {
            return DEFAULT_KICK_REASON;
        }

        int maxLength = serverConfig.getMaxQuitMessageLength();
        int available = maxLength - KICK_MESSAGE_PREFIX.length();
        return (maxLength > 0 && cleaned.length() > available)
                ? cleaned.substring(0, available)
                : cleaned;
    }

    /**
     * Returns the current relay status and statistics.
     *
     * @return relay status information
     */
    @Operation(summary = "Get relay status", description = "Returns relay mode status and statistics including active connections, "
            + "bytes relayed, and V086 relay ports. Returns disabled status if relay mode "
            + "is not enabled.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Relay status retrieved", content = @Content(schema = @Schema(implementation = RelayStatusDTO.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized - invalid credentials", content = @Content)})
    @GetMapping("/relay")
    public RelayStatusDTO getRelayStatus() {
        if (relayController.isEmpty() || relayConfig.isEmpty()) {
            return RelayStatusDTO.disabled();
        }

        KailleraRelayController relay = relayController.get();
        RelayConfig config = relayConfig.get();

        List<RelayStatusDTO.V086RelayDTO> v086Relays = relay.getV086Relays().entrySet().stream()
                .map(entry -> new RelayStatusDTO.V086RelayDTO(entry.getKey(),
                        entry.getValue().getActiveConnections(),
                        entry.getValue().getLastClientMessageNumber(),
                        entry.getValue().getLastServerMessageNumber()))
                .collect(Collectors.toList());

        long uptimeMinutes = relay.isRunning()
                ? (System.currentTimeMillis() - relay.getStartTime()) / 60000
                : 0;

        return new RelayStatusDTO(config.isEnabled(), relay.isRunning(), relay.getListenPort(),
                config.getBackendHost(), config.getBackendPort(), relay.getActiveConnections(),
                relay.getTotalConnections(), relay.getBytesRelayed(), relay.getParseErrors(),
                uptimeMinutes, v086Relays);
    }
}
