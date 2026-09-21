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

    /**
     * True while this thread is already resolving an option through MicroProfile Config. Consulting
     * Config can construct or query config sources — including this one — so the second entry must
     * answer from defaults rather than ask again.
     */
    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Higher ordinal = higher priority. 300 > default sources (application.properties = 250). */
    public static final int ORDINAL = 300;

    private final AtomicReference<Map<String, String>> secretsRef =
            new AtomicReference<>(Collections.emptyMap());

    private BellaPollingProvider poller;

    /** Guards the one-shot initialization below. */
    private volatile boolean initialized;

    /**
     * No-arg constructor required by the SPI.
     *
     * <p><b>It deliberately does nothing.</b> Quarkus instantiates every registered
     * {@code ConfigSource} through the SPI <i>while building the Config</i>, so anything this
     * constructor asks of {@link org.eclipse.microprofile.config.ConfigProvider} re-enters the very
     * discovery that created it: {@code getConfig()} → SPI scan → {@code new BellaConfigSource()}
     * → {@code getConfig()} → … until the stack is gone.
     *
     * <p>That is what used to happen. The build failed with
     * {@code ServiceConfigurationError: Provider io.bellabaxter.quarkus.BellaConfigSource could not
     * be instantiated} wrapping a {@link StackOverflowError}, so no Quarkus application carrying
     * this dependency could be built at all.
     *
     * <p>Work moved to {@link #ensureInitialized()}, which runs on the first property READ — by
     * which time Config exists and asking it a question is safe.
     */
    public BellaConfigSource() {
        // Intentionally empty. See the Javadoc: constructing this type must have no side effects.
    }

    /**
     * Resolves options and loads secrets once, on first use.
     *
     * <p>{@code initialized} is set <b>before</b> the body runs, not after. A re-entrant call — Config
     * being consulted while we are still resolving our own options — then sees "already done" and
     * returns an empty map instead of recursing. Setting it afterwards would reinstate the loop this
     * class exists to avoid.
     */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            initialized = true;
            try {
                initialize();
            } catch (Throwable t) {
                // Throwable, not Exception: this path is reached during Config bootstrap, where the
                // failure mode is an Error rather than an Exception. A config source that cannot
                // load must degrade to empty, never take the application down.
                LOG.log(Level.WARNING, "bella-quarkus: initialization failed, secrets will be empty", t);
            }
        }
    }

    private void initialize() {
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
        ensureInitialized();
        return Collections.unmodifiableMap(secretsRef.get());
    }

    @Override
    public Set<String> getPropertyNames() {
        ensureInitialized();
        return Collections.unmodifiableSet(secretsRef.get().keySet());
    }

    @Override
    public String getValue(String propertyName) {
        ensureInitialized();
        return secretsRef.get().get(propertyName);
    }

    // getName() and getOrdinal() deliberately do NOT initialize: Config calls both while it is
    // still assembling its source list, which is exactly the window this class must stay inert in.

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

    /**
     * Resolves one option: system property, then environment variable, then MicroProfile Config,
     * then the default.
     *
     * <p><b>The order is the fix.</b> It used to ask Config FIRST and fall back to the environment,
     * with {@code catch (Exception)} meant to absorb the bootstrap case — the comment there said
     * "we ARE the config source being bootstrapped", so the hazard was understood. But the recursion
     * it guarded against raises {@link StackOverflowError}, which is an {@link Error} and not an
     * {@link Exception}, so the catch never fired and the guard was decorative.
     *
     * <p>Now the two sources that need no bootstrap answer first, and Config is consulted only if
     * they do not — and behind {@code RESOLVING}, so a Config implementation that reads its own
     * sources while being built cannot come back round.
     */
    private static String resolveConfig(String mpKey, String envKey, String defaultValue) {
        String sys = System.getProperty(mpKey);
        if (sys != null && !sys.isBlank()) {
            return sys;
        }

        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env;
        }

        if (!RESOLVING.get()) {
            RESOLVING.set(Boolean.TRUE);
            try {
                org.eclipse.microprofile.config.Config config =
                        org.eclipse.microprofile.config.ConfigProvider.getConfig();
                return config.getOptionalValue(mpKey, String.class).orElse(defaultValue);
            } catch (Throwable ignored) {
                // Config is not available yet, or asking re-entered. Either way the default stands.
            } finally {
                RESOLVING.remove();
            }
        }

        return defaultValue;
    }
}
