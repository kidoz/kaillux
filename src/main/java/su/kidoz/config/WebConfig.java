package su.kidoz.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for the admin dashboard.
 *
 * <p>
 * The Vite build uses {@code /admin/} as its base, so the entry point is served
 * from {@code static/admin/index.html}. Spring Boot only maps a welcome page at
 * the application root, so redirect the dashboard URLs to that file explicitly.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/admin", "/admin/index.html");
        registry.addRedirectViewController("/admin/", "/admin/index.html");
    }
}
