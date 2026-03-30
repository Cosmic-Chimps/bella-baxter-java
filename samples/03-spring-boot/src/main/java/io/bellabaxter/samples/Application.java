package io.bellabaxter.samples;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal Spring Boot app — shows Bella Baxter secrets available via @Value
 * after the auto-configuration loads them.
 *
 * <p>With bella-baxter-spring-boot on the classpath and {@code bellabaxter.api-key}
 * set, no boilerplate is required. Secrets are injected directly into @Value fields.
 *
 * <p>To enable live-reload on secret changes add to application.properties:
 * <pre>
 *   bellabaxter.polling.enabled=true
 *   bellabaxter.polling.interval-seconds=30
 * </pre>
 * and add {@code spring-cloud-starter-context} to the POM, then annotate beans
 * with {@code @RefreshScope} to re-create them when secrets change.
 */
@SpringBootApplication
@RestController
public class Application {

    // Injected from Bella secrets (auto-loaded by bella-baxter-spring-boot)
    @Value("${DATABASE_URL:(not set)}")
    private String databaseUrl;

    @Value("${EXTERNAL_API_KEY:(not set)}")
    private String externalApiKey;

    @Value("${PORT:(not set)}")
    private String port;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of(
                "DATABASE_URL", databaseUrl,
                "EXTERNAL_API_KEY", externalApiKey,
                "PORT", port
        );
    }

    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }
}
