package su.kidoz.kaillera.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import su.kidoz.kaillera.admin.AdminApiClient.ApiResponse;
import su.kidoz.kaillera.admin.dto.RelayStatusDTO;
import su.kidoz.kaillera.admin.dto.ServerInfoDTO;

/**
 * Integration tests that exercise every API endpoint over real HTTP against a
 * running server instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "admin.username=test-admin", "admin.password=test-password",
        "master-list.touch-kaillera=false", "master-list.touch-emulinker=false",
        "controllers.bind-addresses=127.0.0.1", "controllers.connect.port=47888",
        "controllers.v086.port-range-start=47889", "controllers.v086.extra-ports=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Admin API Integration Tests")
class AdminApiIntegrationTest {

    private static final String USERNAME = "test-admin";
    private static final String PASSWORD = "test-password";

    @LocalServerPort
    private int port;

    private String baseUrl;
    private AdminApiClient client;

    @BeforeEach
    void setUp() {
        baseUrl = "http://127.0.0.1:" + port;
        client = new AdminApiClient(baseUrl, USERNAME, PASSWORD);
    }

    @Test
    @DisplayName("all admin endpoints are reachable with valid credentials")
    void adminEndpointsAreReachable() {
        ServerInfoDTO serverInfo = client.serverInfo();
        assertNotNull(serverInfo);
        assertTrue(serverInfo.running());
        assertEquals(47888, serverInfo.connectPort());
        assertEquals(25, serverInfo.maxUsers());

        assertNotNull(client.users());
        assertNotNull(client.games());
        assertFalse(client.controllers().isEmpty());
        assertNotNull(client.eventMetrics());

        RelayStatusDTO relay = client.relayStatus();
        assertFalse(relay.enabled());
        assertFalse(relay.running());
    }

    @Test
    @DisplayName("admin endpoints return 404 for unknown users")
    void unknownUserEndpointsReturnNotFound() {
        int unknownUser = Integer.MAX_VALUE;

        ApiResponse metrics = client.userEventMetrics(unknownUser);
        assertEquals(404, metrics.status());

        ApiResponse kick = client.kickUser(unknownUser, "integration test");
        assertEquals(404, kick.status());
    }

    @Test
    @DisplayName("v1 admin alias exposes the same API")
    void v1AdminAliasIsAvailable() {
        ApiResponse response = client.rawGet("/api/v1/admin/server-info");

        assertEquals(200, response.status());
        assertNotNull(response.as(ServerInfoDTO.class));
    }

    @Test
    @DisplayName("health, metrics, and OpenAPI endpoints are public")
    void publicEndpointsAreReachable() {
        assertEquals(200, client.health().status());
        assertEquals(200, client.liveness().status());
        assertEquals(200, client.readiness().status());
        assertEquals(200, client.metrics().status());
        assertEquals(200, client.apiDocs().status());
    }

    @Test
    @DisplayName("admin endpoints reject invalid credentials")
    void invalidCredentialsAreRejected() {
        AdminApiClient wrongPassword = new AdminApiClient(baseUrl, USERNAME, "wrong-password");
        assertEquals(401, wrongPassword.rawGet("/api/admin/server-info").status());

        AdminApiClient anonymous = new AdminApiClient(baseUrl, null, null);
        assertEquals(401, anonymous.rawGet("/api/admin/server-info").status());
    }
}
