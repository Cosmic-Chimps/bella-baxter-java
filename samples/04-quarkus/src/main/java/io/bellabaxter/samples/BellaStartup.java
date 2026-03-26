package io.bellabaxter.samples;

import io.bellabaxter.BaxterClient;
import io.bellabaxter.BaxterClientOptions;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Optional;

/**
 * CDI bean that loads Bella Baxter secrets at Quarkus startup.
 *
 * @ApplicationScoped + @Observes StartupEvent is the idiomatic Quarkus
 * way to run code once when the application is ready — equivalent to
 * Spring Boot's ApplicationRunner or CommandLineRunner.
 *
 * Secrets are stored in BellaSecretsStore (also @ApplicationScoped)
 * and accessed via dependency injection in all beans.
 */
@ApplicationScoped
public class BellaStartup {

    private static final Logger log = Logger.getLogger(BellaStartup.class);

    @ConfigProperty(name = "bella.url", defaultValue = "http://localhost:5000")
    String bellaUrl;

    @ConfigProperty(name = "bella.api-key")
    Optional<String> apiKey;

    @Inject
    BellaSecretsStore secretsStore;

    void onStart(@Observes StartupEvent event) {
        if (apiKey.isEmpty() || apiKey.get().isBlank()) {
            log.debug("BellaSecrets: bella.api-key not configured — skipping");
            return;
        }

        BaxterClient client = new BaxterClient(new BaxterClientOptions.Builder()
                .baxterUrl(bellaUrl)
                .apiKey(apiKey.get())
                .build());

        try {
            var resp = client.getAllSecrets();
            secretsStore.init(resp.getSecrets());
            var ctx = client.getKeyContext();
            log.infof("BellaSecrets: loaded %d secret(s) from project '%s' / environment '%s'",
                    resp.getSecrets().size(), ctx.projectSlug, ctx.environmentSlug);
        } catch (Exception e) {
            log.warnf("BellaSecrets: failed to load secrets — %s", e.getMessage());
            // Don't crash Quarkus startup; app may have fallback values
        }
    }
}
