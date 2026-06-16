package io.bellabaxter.spring;

import io.bellabaxter.BaxterClient;
import io.bellabaxter.BaxterClientOptions;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * {@link EnvironmentPostProcessor} that eagerly loads Bella Baxter secrets into the
 * Spring {@link ConfigurableEnvironment} <em>before</em> any beans are created.
 *
 * <p>This ensures that {@code @Value} fields, constructor injection, and
 * {@code application.properties} placeholders (e.g. {@code ${DATABASE_URL}}) all resolve
 * correctly at startup — something a regular {@code @Bean} auto-configuration cannot
 * guarantee because bean creation happens too late.
 *
 * <p>Activated only when {@code bellabaxter.api-key} is set to a non-empty value.
 */
public class BellaSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        // ── API key mode (bax-... token, HMAC auth) ──────────────────────────
        // Resolved from: bellabaxter.api-key property → ${BELLA_API_KEY:} in app.properties
        String apiKey = environment.getProperty("bellabaxter.api-key");

        // ── JWT Bearer mode (OAuth2 access token, injected by bella sdk run) ─
        // Resolved from: bellabaxter.access-token property → ${BELLA_BAXTER_ACCESS_TOKEN:}
        // or directly from the process environment variable as a fallback.
        String accessToken = environment.getProperty("bellabaxter.access-token");
        if (accessToken == null || accessToken.isBlank()) {
            accessToken = System.getenv("BELLA_BAXTER_ACCESS_TOKEN");
        }

        boolean hasApiKey = apiKey != null && !apiKey.isBlank();
        boolean hasJwt    = accessToken != null && !accessToken.isBlank();

        if (!hasApiKey && !hasJwt) {
            return; // nothing to do — auto-configuration will also skip
        }

        String url = environment.getProperty("bellabaxter.url", "https://api.bella-baxter.io");
        int timeoutSeconds;
        try {
            timeoutSeconds = Integer.parseInt(
                    environment.getProperty("bellabaxter.timeout-seconds", "10"));
        } catch (NumberFormatException e) {
            timeoutSeconds = 10;
        }

        BaxterClientOptions.Builder builder = new BaxterClientOptions.Builder()
                .baxterUrl(url)
                .timeoutSeconds(timeoutSeconds);

        if (hasApiKey) {
            builder.apiKey(apiKey);
        } else {
            // JWT mode — resolve project + environment from properties or env vars
            String projectSlug = environment.getProperty("bellabaxter.project");
            if (projectSlug == null || projectSlug.isBlank()) {
                projectSlug = System.getenv("BELLA_BAXTER_PROJECT");
            }
            String environmentSlug = environment.getProperty("bellabaxter.environment");
            if (environmentSlug == null || environmentSlug.isBlank()) {
                environmentSlug = System.getenv("BELLA_BAXTER_ENV");
            }
            if (projectSlug == null || projectSlug.isBlank()
                    || environmentSlug == null || environmentSlug.isBlank()) {
                // Missing project/env — cannot fetch secrets; skip silently
                return;
            }
            builder.bearerToken(accessToken)
                   .projectSlug(projectSlug)
                   .environmentSlug(environmentSlug);
        }

        BaxterClient client = new BaxterClient(builder.build());
        BellaSecretsPropertySource propertySource = new BellaSecretsPropertySource(client);

        // addFirst so Bella secrets override application.properties / env vars
        environment.getPropertySources().addFirst(propertySource);
    }
}
