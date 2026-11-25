package su.kidoz.kaillera.admin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import jakarta.servlet.Filter;
import su.kidoz.config.AuthFailureLimiter;
import su.kidoz.config.SecurityConfig;
import su.kidoz.config.ServerConfig;
import su.kidoz.kaillera.controller.connectcontroller.ConnectController;
import su.kidoz.kaillera.release.KailleraServerReleaseInfo;
import su.kidoz.kaillera.service.GameService;
import su.kidoz.kaillera.service.UserService;
import su.kidoz.util.EmuLinkerExecutor;

/**
 * Security tests for the admin REST API, run through the real security filter
 * chain from {@link SecurityConfig}.
 *
 * <p>
 * Only the controller's collaborators are mocked; authentication, authorization
 * and the response headers are the server's own.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {SecurityConfig.class, AdminSecurityTest.ControllerConfig.class})
@WebAppConfiguration
@TestPropertySource(properties = {"admin.username=operator",
        "admin.password=correct horse battery staple"})
@DisplayName("Admin API Security Tests")
class AdminSecurityTest {

    private static final String USERNAME = "operator";

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private UserService userService;

    @Autowired
    private FilterChainProxy securityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity())
                .build();
    }

    @Test
    @DisplayName("a request without credentials is refused with a plain 401")
    void aRequestWithoutCredentialsIsRefused() throws Exception {
        mockMvc.perform(get("/api/admin/server-info")).andExpect(status().isUnauthorized())
                // A Basic challenge opens the browser's own login dialog, which
                // keeps the dashboard from reporting a rejected password.
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    @DisplayName("a wrong password is refused")
    void aWrongPasswordIsRefused() throws Exception {
        mockMvc.perform(get("/api/admin/server-info").with(httpBasic(USERNAME, "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the right credentials are accepted")
    void theRightCredentialsAreAccepted() throws Exception {
        mockMvc.perform(get("/api/admin/server-info").with(httpBasic(USERNAME, PASSWORD))
                .accept(MediaType.APPLICATION_JSON)).andExpect(status().isOk())
                .andExpect(jsonPath("$.serverName").value("Kaillux"));
    }

    @Test
    @DisplayName("the /api/v1/admin prefix is protected the same way")
    void theV1PrefixIsProtectedTheSameWay() throws Exception {
        mockMvc.perform(get("/api/v1/admin/server-info")).andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/server-info").with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a state-changing endpoint needs credentials and is never reached without them")
    void aStateChangingEndpointNeedsCredentials() throws Exception {
        mockMvc.perform(kickRequest()).andExpect(status().isUnauthorized());

        verify(userService, never()).quit(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("paths outside the admin API stay public")
    void pathsOutsideTheAdminApiStayPublic() throws Exception {
        // No controller answers this path here, so the 404 comes from Spring MVC.
        // It shows the request got past the security chain.
        mockMvc.perform(get("/healthz")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("responses carry a strict Content Security Policy")
    void responsesCarryAStrictContentSecurityPolicy() throws Exception {
        String policy = mockMvc
                .perform(get("/api/admin/server-info").with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isOk()).andExpect(header().exists("Content-Security-Policy"))
                .andReturn().getResponse().getHeader("Content-Security-Policy");

        assertNotNull(policy);
        // Scripts may only come from the server: the dashboard keeps its credential
        // where any script on the origin could read it.
        assertTrue(policy.contains("script-src 'self'"), policy);
        assertFalse(policy.contains("script-src 'self' 'unsafe-inline'"), policy);
        assertFalse(policy.contains("unsafe-eval"), policy);
        assertTrue(policy.contains("frame-ancestors 'none'"), policy);
        assertTrue(policy.contains("object-src 'none'"), policy);
    }

    @Test
    @DisplayName("the admin API is not cacheable and may not be framed")
    void theAdminApiIsNotCacheableAndMayNotBeFramed() throws Exception {
        mockMvc.perform(get("/api/admin/server-info").with(httpBasic(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("the failure limiter runs before the password is checked")
    void theFailureLimiterRunsBeforeThePasswordIsChecked() {
        List<Filter> filters = securityFilterChain.getFilterChains().stream()
                .map(SecurityFilterChain::getFilters).findFirst().orElse(List.of());

        int limiter = -1;
        int authentication = -1;
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i) instanceof AuthFailureLimiter && limiter < 0) {
                limiter = i;
            }
            if (filters.get(i) instanceof BasicAuthenticationFilter && authentication < 0) {
                authentication = i;
            }
        }

        assertTrue(limiter >= 0, "the limiter must be in the chain");
        assertTrue(authentication >= 0, "Basic authentication must be in the chain");
        assertTrue(limiter < authentication,
                "a locked address must be refused before the password is hashed");
    }

    private static MockHttpServletRequestBuilder kickRequest() {
        return post("/api/admin/users/1/kick").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"test\"}");
    }

    /**
     * The controller with mocked collaborators, wired by Spring like production.
     */
    @Configuration
    @EnableWebMvc
    static class ControllerConfig {

        @Bean
        UserService userService() {
            return mock(UserService.class);
        }

        @Bean
        GameService gameService() {
            return mock(GameService.class);
        }

        @Bean
        KailleraServerReleaseInfo releaseInfo() {
            KailleraServerReleaseInfo releaseInfo = mock(KailleraServerReleaseInfo.class);
            when(releaseInfo.getProductName()).thenReturn("Kaillux");
            when(releaseInfo.getVersionString()).thenReturn("1.4.0");
            return releaseInfo;
        }

        @Bean
        ConnectController connectController() {
            return mock(ConnectController.class);
        }

        @Bean
        EmuLinkerExecutor executor() {
            return mock(EmuLinkerExecutor.class);
        }

        @Bean
        ServerConfig serverConfig() {
            return new ServerConfig();
        }

        @Bean
        AdminRestController adminRestController(UserService userService, GameService gameService,
                KailleraServerReleaseInfo releaseInfo, ConnectController connectController,
                EmuLinkerExecutor executor, ServerConfig serverConfig) {
            return new AdminRestController(userService, gameService, releaseInfo, connectController,
                    executor, Optional.empty(), Optional.empty(), serverConfig);
        }
    }
}
