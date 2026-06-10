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
        String apiKey = environment.getProperty("bellabaxter.api-key");
        if (apiKey == null || apiKey.isBlank()) {
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

        BaxterClientOptions options = new BaxterClientOptions.Builder()
                .baxterUrl(url)
                .apiKey(apiKey)
                .timeoutSeconds(timeoutSeconds)
                .build();

        BaxterClient client = new BaxterClient(options);
        BellaSecretsPropertySource propertySource = new BellaSecretsPropertySource(client);

        // addFirst so Bella secrets override application.properties / env vars
        environment.getPropertySources().addFirst(propertySource);
    }
}
