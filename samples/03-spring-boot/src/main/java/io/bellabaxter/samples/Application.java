package io.bellabaxter.samples;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal Spring Boot app — shows secrets available via @Value after
 * BellaEnvironmentPostProcessor runs at startup.
 */
@SpringBootApplication
@RestController
public class Application {

    // These are injected from Bella secrets (or application.properties fallback)
    @Value("${PORT:(not set)}")
    private String port;

    @Value("${DATABASE_URL:(not set)}")
    private String databaseUrl;

    @Value("${EXTERNAL_API_KEY:(not set)}")
    private String externalApiKey;

    @Value("${GLEAP_API_KEY:(not set)}")
    private String gleapApiKey;

    @Value("${ENABLE_FEATURES:(not set)}")
    private String enableFeatures;

    @Value("${APP_ID:(not set)}")
    private String appId;

    @Value("${ConnectionStrings__Postgres:(not set)}")
    private String connectionStringsPostgres;

    @Value("${APP_CONFIG:(not set)}")
    private String appConfig;

    @Autowired
    private BellaAppSecrets secrets;

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of(
                "PORT", port,
                "DATABASE_URL", databaseUrl,
                "EXTERNAL_API_KEY", externalApiKey,
                "GLEAP_API_KEY", gleapApiKey,
                "ENABLE_FEATURES", enableFeatures,
                "APP_ID", appId,
                "ConnectionStrings__Postgres", connectionStringsPostgres,
                "APP_CONFIG", appConfig
        );
    }

    @GetMapping("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    /** Typed access via @ConfigurationProperties(prefix = "bellabaxter") */
    @GetMapping("/typed")
    public Map<String, String> typed() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        result.put("PORT", secrets.getPort());
        result.put("DATABASE_URL", secrets.getDatabaseUrl());
        result.put("EXTERNAL_API_KEY", secrets.getExternalApiKey());
        result.put("GLEAP_API_KEY", secrets.getGleapApiKey());
        result.put("ENABLE_FEATURES", secrets.getEnableFeatures());
        result.put("APP_ID", secrets.getAppId());
        result.put("ConnectionStrings__Postgres", secrets.getConnectionStrings().getPostgres());
        result.put("APP_CONFIG", secrets.getAppConfig());
        return result;
    }
}
