package su.kidoz.kaillera.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import su.kidoz.kaillera.admin.dto.ControllerDTO;
import su.kidoz.kaillera.admin.dto.EventMetricsDTO;
import su.kidoz.kaillera.admin.dto.GameDTO;
import su.kidoz.kaillera.admin.dto.KickUserRequest;
import su.kidoz.kaillera.admin.dto.RelayStatusDTO;
import su.kidoz.kaillera.admin.dto.ServerInfoDTO;
import su.kidoz.kaillera.admin.dto.UserDTO;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal HTTP client for the Kaillera API, intended for integration tests.
 *
 * <p>
 * Every admin endpoint is exposed as a typed method. Lower-level
 * {@link #rawGet(String)} and {@link #adminGet(String)} calls return an
 * {@link ApiResponse} so tests can assert status codes such as 401 or 404.
 * Requests are sent with HTTP Basic authentication when credentials are
 * supplied; passing {@code null} credentials demonstrates the anonymous case.
 */
public final class AdminApiClient {

    private static final String ADMIN_PREFIX = "/api/admin";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String authorization;

    public AdminApiClient(String baseUrl, String username, String password) {
        this(baseUrl, username, password, JsonMapper.builder().build());
    }

    public AdminApiClient(String baseUrl, String username, String password,
            ObjectMapper objectMapper) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        this.authorization = basicAuth(username, password);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // Admin API

    public ServerInfoDTO serverInfo() {
        return adminGet("/server-info").as(ServerInfoDTO.class);
    }

    public List<UserDTO> users() {
        return adminGet("/users").asList(UserDTO.class);
    }

    public List<GameDTO> games() {
        return adminGet("/games").asList(GameDTO.class);
    }

    public List<ControllerDTO> controllers() {
        return adminGet("/controllers").asList(ControllerDTO.class);
    }

    public List<EventMetricsDTO> eventMetrics() {
        return adminGet("/event-metrics").asList(EventMetricsDTO.class);
    }

    public ApiResponse userEventMetrics(int userId) {
        return adminGet("/users/" + userId + "/events");
    }

    public ApiResponse kickUser(int userId, String reason) {
        String body = objectMapper.writeValueAsString(new KickUserRequest(reason));
        return adminPost("/users/" + userId + "/kick", body);
    }

    public RelayStatusDTO relayStatus() {
        return adminGet("/relay").as(RelayStatusDTO.class);
    }

    // Public endpoints

    public ApiResponse health() {
        return rawGet("/healthz");
    }

    public ApiResponse liveness() {
        return rawGet("/healthz/liveness");
    }

    public ApiResponse readiness() {
        return rawGet("/healthz/readiness");
    }

    public ApiResponse metrics() {
        return rawGet("/metrics");
    }

    public ApiResponse apiDocs() {
        return rawGet("/api-docs");
    }

    // Low-level requests

    public ApiResponse rawGet(String path) {
        return request("GET", path, null);
    }

    public ApiResponse adminGet(String path) {
        return rawGet(ADMIN_PREFIX + path);
    }

    public ApiResponse adminPost(String path, String jsonBody) {
        return request("POST", ADMIN_PREFIX + path, jsonBody);
    }

    private ApiResponse request(String method, String path, String jsonBody) {
        // Accept any representation: JSON for the admin API, plain text for metrics.
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(REQUEST_TIMEOUT).header("Accept", "*/*");

        if (authorization != null) {
            builder.header("Authorization", authorization);
        }

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        try {
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new ApiResponse(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Request failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Request interrupted: " + method + " " + path, e);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String basicAuth(String username, String password) {
        if (username == null || password == null) {
            return null;
        }
        String credentials = username + ":" + password;
        return "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Status code and raw body of an API response, with typed decoding helpers.
     */
    public final class ApiResponse {

        private final int status;
        private final String body;

        private ApiResponse(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }

        public boolean isSuccess() {
            return status >= 200 && status < 300;
        }

        public ApiResponse requireSuccess() {
            if (!isSuccess()) {
                throw new AssertionError("Expected 2xx response but got " + status + ": " + body);
            }
            return this;
        }

        public <T> T as(Class<T> type) {
            requireSuccess();
            return objectMapper.readValue(body, type);
        }

        public <T> List<T> asList(Class<T> elementType) {
            requireSuccess();
            JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class,
                    elementType);
            return objectMapper.readValue(body, listType);
        }
    }
}
