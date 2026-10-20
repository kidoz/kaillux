package su.kidoz.kaillera.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO for server information response.
 */
@Schema(description = "Comprehensive server status information")
public record ServerInfoDTO(
        @Schema(description = "Server name", example = "Kaillux Server") String serverName,
        @Schema(description = "Server version", example = "1.4.0") String version,
        @Schema(description = "Build number", example = "130") int build,
        @Schema(description = "Whether the server is running", example = "true") boolean running,
        @Schema(description = "UDP connection port", example = "27888") int connectPort,
        @Schema(description = "Server uptime in minutes", example = "1440") long uptimeMinutes,
        @Schema(description = "Current number of connected users", example = "5") int userCount,
        @Schema(description = "Maximum allowed users", example = "25") int maxUsers,
        @Schema(description = "Current number of active games", example = "2") int gameCount,
        @Schema(description = "Maximum allowed games (0 = unlimited)", example = "0") int maxGames,
        @Schema(description = "Connection statistics") StatsDTO stats,
        @Schema(description = "Thread pool metrics") ThreadPoolDTO threadPool) {

    /**
     * Nested DTO for connection statistics.
     */
    @Schema(description = "Connection and request statistics")
    public record StatsDTO(
            @Schema(description = "Total connection requests received", example = "1000") int requestCount,
            @Schema(description = "Successful connections", example = "950") int connectCount,
            @Schema(description = "Protocol errors encountered", example = "10") int protocolErrors,
            @Schema(description = "Connections denied due to server full", example = "30") int deniedFull,
            @Schema(description = "Connections denied for other reasons", example = "10") int deniedOther) {
    }

    /**
     * Nested DTO for thread pool status.
     */
    @Schema(description = "Thread pool status for virtual thread executor")
    public record ThreadPoolDTO(
            @Schema(description = "Currently active threads", example = "10") int active,
            @Schema(description = "Current pool size", example = "50") long poolSize,
            @Schema(description = "Maximum pool size", example = "100") int maxPoolSize,
            @Schema(description = "Total tasks executed", example = "5000") long taskCount) {
    }
}
