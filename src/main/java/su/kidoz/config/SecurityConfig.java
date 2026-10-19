package su.kidoz.config;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

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

    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void validateCredentials() {
        // Never fall back to a well-known default password. When none is
        // configured, generate one so the admin API is not reachable with
        // published credentials.
        if (adminPassword == null || adminPassword.isBlank()) {
            adminPassword = generateRandomPassword();
            log.warn("*".repeat(70));
            log.warn("No admin password configured; generated random admin password: {}",
                    adminPassword);
            log.warn("Set admin.password (or ADMIN_PASSWORD) to choose your own.");
            log.warn("*".repeat(70));
        }

        boolean insecureUsername = DEFAULT_USERNAME.equals(adminUsername);
        boolean insecurePassword = INSECURE_PASSWORDS.stream()
                .anyMatch(p -> p.equalsIgnoreCase(adminPassword));

        if (insecureUsername || insecurePassword) {
            String message = buildSecurityWarningMessage(insecureUsername, insecurePassword);

            // Check if running in production profile or if failOnInsecureCredentials is set
            boolean isProduction = List.of(environment.getActiveProfiles()).contains("prod")
                    || List.of(environment.getActiveProfiles()).contains("production");

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
                        // Exposed actuator endpoints when base-path is "/"
                        .requestMatchers("/metrics", "/healthz").permitAll()
                        // Actuator endpoints - health/info are public, others require auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        // Static resources and other endpoints are public
                        .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());

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
}
