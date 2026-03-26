package io.bellabaxter.samples;

import io.bellabaxter.BaxterClient;
import io.bellabaxter.BaxterClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Spring Boot EnvironmentPostProcessor — loads Bella Baxter secrets into
 * Spring's Environment BEFORE any beans are created.
 *
 * This means secrets are available via:
 *   - @Value("${DATABASE_URL}")
 *   - application.properties placeholders: spring.datasource.url=${DATABASE_URL}
 *   - Environment.getProperty("DATABASE_URL")
 *
 * Registration (src/main/resources/META-INF/spring.factories or Spring Boot 2.7+ spring/org.springframework.boot.env.EnvironmentPostProcessor):
 *   org.springframework.boot.env.EnvironmentPostProcessor=io.bellabaxter.samples.BellaEnvironmentPostProcessor
 */
public class BellaEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BellaEnvironmentPostProcessor.class);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String apiKey    = environment.getProperty("BELLA_BAXTER_API_KEY",
                           environment.getProperty("BELLA_API_KEY",
                           environment.getProperty("bella.api-key", "")));
        String url       = environment.getProperty("BELLA_BAXTER_URL",
                           environment.getProperty("bella.url", "http://localhost:5000"));

        if (apiKey == null || apiKey.isBlank()) {
            log.debug("BellaSecrets: BELLA_BAXTER_API_KEY not set — skipping");
            return;
        }

        BaxterClient client = new BaxterClient(new BaxterClientOptions.Builder()
                .baxterUrl(url)
                .apiKey(apiKey)
                .build());

        try {
            var resp = client.getAllSecrets();
            Map<String, Object> secrets = new java.util.HashMap<>(resp.getSecrets());

            // 1. Raw keys — for @Value("${DATABASE_URL}") and application.properties placeholders
            environment.getPropertySources().addLast(
                    new MapPropertySource("bellabaxter", secrets)
            );

            // 2. Namespaced keys — for @ConfigurationProperties(prefix = "bellabaxter")
            //    PORT                       → bellabaxter.PORT     (→ field `port`)
            //    DATABASE_URL               → bellabaxter.DATABASE_URL  (→ field `databaseUrl`)
            //    ConnectionStrings__Postgres → bellabaxter.ConnectionStrings.Postgres (→ nested `connectionStrings.postgres`)
            //    Note: __ must be converted to . here; MapPropertySource doesn't apply
            //    the OS env-var __ nesting convention that @ConfigurationProperties expects.
            Map<String, Object> prefixed = new java.util.HashMap<>();
            secrets.forEach((k, v) -> prefixed.put("bellabaxter." + k.replace("__", "."), v));
            environment.getPropertySources().addLast(
                    new MapPropertySource("bellabaxter-typed", prefixed)
            );

            var ctx = client.getKeyContext();
            log.info("BellaSecrets: loaded {} secret(s) from project '{}' / environment '{}'",
                    secrets.size(), ctx.projectSlug, ctx.environmentSlug);
        } catch (Exception e) {
            log.warn("BellaSecrets: failed to load secrets — {}", e.getMessage());
            // Don't crash Spring Boot startup; app may have fallback values
        }
    }

    @Override
    public int getOrder() {
        // Run after application.properties is loaded (Ordered.LOWEST_PRECEDENCE - 5)
        // so Spring-managed properties take priority over Bella secrets
        return Ordered.LOWEST_PRECEDENCE - 5;
    }
}
