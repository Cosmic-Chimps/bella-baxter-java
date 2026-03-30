package io.bellabaxter.quarkus;

import io.bellabaxter.BaxterClient;
import io.bellabaxter.BaxterClientOptions;
import io.bellabaxter.BellaPollingProvider;
import io.bellabaxter.SecretChange;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MicroProfile {@link ConfigSource} backed by Bella Baxter secrets.
 *
 * <p>Registered via the Java SPI mechanism:
 * {@code META-INF/services/org.eclipse.microprofile.config.spi.ConfigSource}.
 *
 * <p>Quarkus re-reads every registered {@code ConfigSource} on each {@code @ConfigProperty}
 * access (or at startup), so secrets updated in the internal map are picked up automatically.
 *
 * <h3>Configuration via environment variables or MicroProfile Config</h3>
 * <pre>
 * BELLABAXTER_URL=https://baxter.example.com
 * BELLABAXTER_API_KEY=bax-...
 * BELLABAXTER_POLLING_ENABLED=true
 * BELLABAXTER_POLLING_INTERVAL_SECONDS=30
 * BELLABAXTER_POLLING_FALLBACK_ON_ERROR=true
 * </pre>
 *
 * <p>Or via {@code application.properties}:
 * <pre>
 * bellabaxter.url=https://baxter.example.com
 * bellabaxter.api-key=bax-...
 * bellabaxter.polling.enabled=true
 * bellabaxter.polling.interval-seconds=30
 * </pre>
 *
 * <h3>Usage in a Quarkus bean</h3>
 * <pre>{@code
 * @ApplicationScoped
 * public class MyService {
 *
 *     @ConfigProperty(name = "DATABASE_URL")
 *     String databaseUrl;
 * }
 * }</pre>
 *
 * <p>When polling is enabled, the {@code DATABASE_URL} value is transparently updated in
 * the backing map whenever Bella Baxter detects a change. The next invocation of any
 * method that reads {@code databaseUrl} will see the updated value.
 */
public class BellaConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(BellaConfigSource.class.getName());

    /** Higher ordinal = higher priority. 300 > default sources (application.properties = 250). */
    public static final int ORDINAL = 300;

    private final AtomicReference<Map<String, String>> secretsRef =
            new AtomicReference<>(Collections.emptyMap());

    private BellaPollingProvider poller;

    /**
     * No-arg constructor required by the SPI. Reads configuration from environment variables
     * or MicroProfile Config properties (see class Javadoc for property names).
     */
    public BellaConfigSource() {
        String url    = resolveConfig("bellabaxter.url",     "BELLABAXTER_URL",    "https://api.bella-baxter.io");
        String apiKey = resolveConfig("bellabaxter.api-key", "BELLABAXTER_API_KEY", null);

        if (apiKey == null || apiKey.isBlank()) {
            LOG.warning("bella-quarkus: bellabaxter.api-key not set — BellaConfigSource will be empty");
            return;
        }

        boolean pollingEnabled = Boolean.parseBoolean(
                resolveConfig("bellabaxter.polling.enabled", "BELLABAXTER_POLLING_ENABLED", "false"));
        int intervalSeconds = Integer.parseInt(
                resolveConfig("bellabaxter.polling.interval-seconds", "BELLABAXTER_POLLING_INTERVAL_SECONDS", "60"));
        boolean fallbackOnError = Boolean.parseBoolean(
                resolveConfig("bellabaxter.polling.fallback-on-error", "BELLABAXTER_POLLING_FALLBACK_ON_ERROR", "true"));

        BaxterClientOptions options = new BaxterClientOptions.Builder()
                .baxterUrl(url)
                .apiKey(apiKey)
                .pollingEnabled(pollingEnabled)
                .pollingInterval(Duration.ofSeconds(intervalSeconds))
                .fallbackOnError(fallbackOnError)
                .build();

        BaxterClient client = new BaxterClient(options);

        if (pollingEnabled) {
            poller = new BellaPollingProvider(client, options);
            poller.addListener(this::applyChanges);
            poller.start();
            secretsRef.set(new HashMap<>(poller.getSecrets()));
        } else {
            try {
                secretsRef.set(new HashMap<>(client.getAllSecrets().getSecrets()));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "bella-quarkus: initial fetch failed, secrets will be empty", e);
            }
        }
    }

    @Override
    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(secretsRef.get());
    }

    @Override
    public Set<String> getPropertyNames() {
        return Collections.unmodifiableSet(secretsRef.get().keySet());
    }

    @Override
    public String getValue(String propertyName) {
        return secretsRef.get().get(propertyName);
    }

    @Override
    public String getName() {
        return "BellaConfigSource";
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private void applyChanges(List<SecretChange> changes) {
        secretsRef.updateAndGet(current -> {
            Map<String, String> updated = new HashMap<>(current);
            for (SecretChange change : changes) {
                switch (change.changeType()) {
                    case ADDED, MODIFIED -> updated.put(change.key(), change.newValue());
                    case REMOVED         -> updated.remove(change.key());
                }
            }
            return Collections.unmodifiableMap(updated);
        });
    }

    private static String resolveConfig(String mpKey, String envKey, String defaultValue) {
        // Try MicroProfile Config (if already bootstrapped)
        try {
            org.eclipse.microprofile.config.Config config =
                    org.eclipse.microprofile.config.ConfigProvider.getConfig();
            return config.getOptionalValue(mpKey, String.class).orElse(
                    System.getenv(envKey) != null ? System.getenv(envKey) : defaultValue);
        } catch (Exception ignored) {
            // Config not bootstrapped yet (we ARE the config source being bootstrapped)
        }
        // Fall back to environment variables
        String env = System.getenv(envKey);
        return env != null ? env : defaultValue;
    }
}
