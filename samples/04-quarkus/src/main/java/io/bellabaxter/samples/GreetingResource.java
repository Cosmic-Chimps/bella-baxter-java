package io.bellabaxter.samples;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Map;
import java.util.Optional;

/**
 * REST resource showing Bella Baxter secrets injected via @ConfigProperty.
 *
 * <p>With bella-baxter-quarkus on the classpath and BELLABAXTER_API_KEY set,
 * {@code BellaConfigSource} registers itself automatically via the Java SPI.
 * Quarkus then injects Bella secrets via standard MicroProfile Config.
 *
 * <p>To enable live-reload on secret changes set:
 * <pre>
 *   BELLABAXTER_POLLING_ENABLED=true
 *   BELLABAXTER_POLLING_INTERVAL_SECONDS=30
 * </pre>
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class GreetingResource {

    // Injected from Bella Baxter secrets via BellaConfigSource (auto-registered SPI)
    @ConfigProperty(name = "DATABASE_URL", defaultValue = "(not set)")
    String databaseUrl;

    @ConfigProperty(name = "EXTERNAL_API_KEY", defaultValue = "(not set)")
    String externalApiKey;

    @ConfigProperty(name = "PORT", defaultValue = "(not set)")
    String port;

    @GET
    public Map<String, String> index() {
        return Map.of(
                "DATABASE_URL", databaseUrl,
                "EXTERNAL_API_KEY", externalApiKey,
                "PORT", port
        );
    }

    @GET
    @Path("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }
}
