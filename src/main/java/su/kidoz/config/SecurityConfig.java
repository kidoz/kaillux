package su.kidoz.config;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import su.kidoz.kaillera.access.InGameAdminGuard;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String DEFAULT_USERNAME = "admin";
    // List of insecure default passwords that should trigger a warning
    private static final List<String> INSECURE_PASSWORDS = List.of("changeme", "admin", "password",
            "123456");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:}")
    private String adminPassword;

    @Value("${admin.security.failOnInsecureCredentials:false}")
    private boolean failOnInsecureCredentials;

    /**
     * How long a successful dashboard login trusts an address for in-game admin.
     */
    @Value("${admin.in-game-admin-ttl-seconds:1800}")
    private long inGameAdminTtlSeconds;

    /** Optional comma-separated wildcard addresses that may use in-game admin. */
    @Value("${admin.trusted-in-game-addresses:}")
    private String trustedInGameAddresses;

    /** Reverse proxies whose X-Forwarded-For header may be believed. */
    @Value("${admin.security.trusted-proxies:}")
    private String trustedProxies;

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validateCredentials() {
        InGameAdminGuard.configure(inGameAdminTtlSeconds, trustedInGameAddresses);

        boolean isProduction = List.of(environment.getActiveProfiles()).contains("prod")
                || List.of(environment.getActiveProfiles()).contains("production");

        // Never fall back to a well-known default password. When none is
        // configured, generate one so the admin API is not reachable with
        // published credentials. In production the generated secret would be
        // captured by the container or journal, so require an explicit one.
        if (adminPassword == null || adminPassword.isBlank()) {
            if (isProduction) {
                log.error("No admin password configured in a production profile");
                throw new SecurityException(
                        "Application startup blocked: set ADMIN_PASSWORD (admin.password) "
                                + "in production; a generated password would be written to "
                                + "captured process output.");
            }
            adminPassword = generateRandomPassword();
            // The password is written to the process console only. Sending it
            // through the logger would store it in every shipped log file.
            String banner = "*".repeat(70);
            System.err.println(banner);
            System.err.println(
                    "No admin password configured. Generated admin password: " + adminPassword);
            System.err.println("Set admin.password (or ADMIN_PASSWORD) to choose your own.");
            System.err.println("This password is not written to the application log.");
            System.err.println(banner);
        }

        boolean insecureUsername = DEFAULT_USERNAME.equals(adminUsername);
        boolean insecurePassword = INSECURE_PASSWORDS.stream()
                .anyMatch(p -> p.equalsIgnoreCase(adminPassword));

        if (insecureUsername || insecurePassword) {
            String message = buildSecurityWarningMessage(insecureUsername, insecurePassword);

            if (failOnInsecureCredentials || isProduction) {
                log.error(message);
                throw new SecurityException(
                        "Application startup blocked: insecure admin credentials detected. "
                                + "Set admin.username and admin.password in application.properties "
                                + "or set admin.security.failOnInsecureCredentials=false to override.");
            } else {
                log.warn(message);
            }
        }
    }

    private static String generateRandomPassword() {
        byte[] bytes = new byte[24];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String buildSecurityWarningMessage(boolean insecureUsername, boolean insecurePassword) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("*".repeat(70));
        sb.append("\n* SECURITY WARNING: Insecure admin credentials detected!");
        sb.append("\n* Change admin.username and admin.password in application.properties");
        if (insecureUsername) {
            sb.append("\n* Current username '").append(adminUsername)
                    .append("' is a default value");
        }
        if (insecurePassword) {
            sb.append("\n* Current password is a commonly used insecure password");
        }
        sb.append("\n* This is a security risk in production environments!");
        sb.append(
                "\n* Set admin.security.failOnInsecureCredentials=true to enforce secure credentials");
        sb.append("\n").append("*".repeat(70));
        return sb.toString();
    }

    /**
     * Content Security Policy sent with every response.
     *
     * <p>
     * The admin origin also serves the public Swagger UI, and the dashboard keeps
     * the credential where any script on the origin could read it, so scripts may
     * only come from the server itself. Styles allow inline because Swagger UI sets
     * them that way.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
            + "connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // CSRF is disabled because this is a stateless REST API using HTTP Basic auth.
        // Each request requires credentials, so CSRF attacks via cookies don't apply.
        // If a browser-based admin UI with session cookies is added, re-enable CSRF.
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Admin API requires authentication (both prefixes)
                        .requestMatchers("/api/admin/**", "/api/v1/admin/**").authenticated()
                        // Prometheus metrics and API documentation describe the
                        // internal surface; they are not public by default.
                        .requestMatchers("/metrics", "/api-docs/**", "/swagger-ui.html",
                                "/swagger-ui/**")
                        .authenticated()
                        // Health probes stay public for orchestrators and monitors.
                        .requestMatchers("/healthz", "/healthz/**", "/actuator/health",
                                "/actuator/health/**", "/actuator/info")
                        .permitAll().requestMatchers("/actuator/**").authenticated()
                        // The dashboard shell and its static assets.
                        .requestMatchers("/admin", "/admin/", "/admin/**").permitAll()
                        // Anything added later is private unless it is listed above.
                        .anyRequest().denyAll())
                .headers(headers -> headers.contentSecurityPolicy(
                        csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY)))
                // Each attempt at the password costs a BCrypt hash, so an address that
                // keeps failing is refused before it reaches authentication.
                .addFilterBefore(new AuthFailureLimiter(trustedProxyAddresses()),
                        BasicAuthenticationFilter.class)
                // In-game admin authority is keyed by UDP source address, which can be
                // spoofed. Remember addresses that completed a real HTTP authentication.
                .addFilterAfter(new InGameAdminAuthenticationFilter(),
                        BasicAuthenticationFilter.class)
                // Return 401 without WWW-Authenticate. A Basic challenge makes
                // the browser show its own login dialog and leaves fetch()
                // waiting, so the admin page cannot report a rejected password.
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response,
                        exception) -> response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)));

        return http.build();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        var admin = User.builder().username(adminUsername)
                .password(passwordEncoder.encode(adminPassword)).roles("ADMIN").build();

        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private java.util.Set<String> trustedProxyAddresses() {
        if (trustedProxies == null || trustedProxies.isBlank()) {
            return java.util.Set.of();
        }
        return java.util.Arrays.stream(trustedProxies.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /**
     * Records the source address of a request that authenticated successfully.
     * In-game admin commands require this proof because a UDP source address can be
     * spoofed without ever receiving the response.
     */
    private static final class InGameAdminAuthenticationFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                FilterChain filterChain) throws ServletException, IOException {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.isAuthenticated()
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                InGameAdminGuard.recordAuthenticatedAddress(request.getRemoteAddr());
            }
            filterChain.doFilter(request, response);
        }
    }
}
