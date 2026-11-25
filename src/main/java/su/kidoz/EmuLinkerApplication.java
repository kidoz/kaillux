package su.kidoz;

import java.util.Date;
import su.kidoz.release.ReleaseInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class EmuLinkerApplication {
    private static final Logger log = LoggerFactory.getLogger(EmuLinkerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(EmuLinkerApplication.class, args);
    }

    @Bean
    public CommandLineRunner startupRunner(ReleaseInfo releaseInfo) {
        return args -> {
            log.info("Kaillux server Starting...");
            log.info("{}", releaseInfo.getWelcome());
            log.info("Kaillux server is running @ {}", new Date());
        };
    }
}
