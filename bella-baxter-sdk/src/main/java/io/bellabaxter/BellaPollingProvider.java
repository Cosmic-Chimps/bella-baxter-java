package io.bellabaxter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Background polling engine that watches for secret changes in Bella Baxter.
 *
 * <p>Uses a lightweight version check on each tick (GET /secrets/version) and only
 * performs a full secret fetch when the version has changed — identical to the .NET
 * {@code BellaPollingProvider} pattern.
 *
 * <p>Thread-safe. Listeners are called on the polling thread.
 *
 * <pre>{@code
 * BaxterClient client = new BaxterClient(new BaxterClientOptions.Builder()
 *     .baxterUrl("https://baxter.example.com")
 *     .apiKey("bax-...")
 *     .pollingEnabled(true)
 *     .pollingInterval(Duration.ofSeconds(30))
 *     .build());
 *
 * try (BellaPollingProvider poller = new BellaPollingProvider(client, client.getOptions())) {
 *     poller.addListener(changes ->
 *         changes.forEach(c -> System.out.println("Changed: " + c.key())));
 *     poller.start();
 *
 *     // secrets available immediately after start()
 *     Map<String, String> secrets = poller.getSecrets();
 * }
 * }</pre>
 */
public final class BellaPollingProvider implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(BellaPollingProvider.class.getName());

    private final BaxterClient client;
    private final BaxterClientOptions options;
    private final CopyOnWriteArrayList<SecretsChangedListener> listeners = new CopyOnWriteArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();
    private volatile Map<String, String> cachedSecrets = Collections.emptyMap();
    private volatile long lastKnownVersion = -1;

    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> pollingTask;

    public BellaPollingProvider(BaxterClient client, BaxterClientOptions options) {
        this.client    = client;
        this.options   = options;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bella-polling");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Add a listener to be notified when secrets change.
     * Safe to call before or after {@link #start()}.
     */
    public void addListener(SecretsChangedListener listener) {
        listeners.add(listener);
    }

    /** Remove a previously registered listener. */
    public void removeListener(SecretsChangedListener listener) {
        listeners.remove(listener);
    }

    /**
     * Start polling. Performs an immediate full fetch, then schedules periodic checks.
     *
     * @throws BaxterClient.BaxterException if the initial fetch fails and fallbackOnError is false
     */
    public void start() {
        // Initial load (synchronous)
        try {
            io.bellabaxter.model.AllEnvironmentSecretsResponse resp = client.getAllSecrets();
            lock.lock();
            try {
                cachedSecrets    = Collections.unmodifiableMap(new HashMap<>(resp.getSecrets()));
                lastKnownVersion = resp.getVersion();
            } finally {
                lock.unlock();
            }
        } catch (Exception e) {
            if (!options.isFallbackOnError()) throw e;
            LOG.log(Level.WARNING, "bella-poller: initial fetch failed, using empty cache", e);
        }

        long intervalMs = options.getPollingInterval().toMillis();
        pollingTask = scheduler.scheduleAtFixedRate(
                this::poll, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Returns the latest cached secrets. Always non-null; empty map before {@link #start()}.
     */
    public Map<String, String> getSecrets() {
        return cachedSecrets;
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private void poll() {
        try {
            io.bellabaxter.model.EnvironmentSecretsVersionResponse versionResp = client.getSecretsVersion();
            long remoteVersion = versionResp.getVersion();

            if (remoteVersion == lastKnownVersion) {
                return; // nothing changed
            }

            io.bellabaxter.model.AllEnvironmentSecretsResponse resp = client.getAllSecrets();
            Map<String, String> newSecrets = new HashMap<>(resp.getSecrets());

            List<SecretChange> changes;
            lock.lock();
            try {
                changes          = detectChanges(cachedSecrets, newSecrets);
                cachedSecrets    = Collections.unmodifiableMap(newSecrets);
                lastKnownVersion = resp.getVersion();
            } finally {
                lock.unlock();
            }

            if (!changes.isEmpty()) {
                notifyListeners(changes);
            }
        } catch (Exception e) {
            if (options.isFallbackOnError()) {
                LOG.log(Level.WARNING, "bella-poller: poll failed, keeping cached secrets", e);
            } else {
                LOG.log(Level.SEVERE, "bella-poller: poll failed", e);
            }
        }
    }

    private static List<SecretChange> detectChanges(
            Map<String, String> oldSecrets,
            Map<String, String> newSecrets) {

        List<SecretChange> changes = new ArrayList<>();

        // Added or modified
        for (Map.Entry<String, String> entry : newSecrets.entrySet()) {
            String key = entry.getKey();
            String newVal = entry.getValue();
            if (!oldSecrets.containsKey(key)) {
                changes.add(new SecretChange(key, SecretChange.ChangeType.ADDED, null, newVal));
            } else {
                String oldVal = oldSecrets.get(key);
                if (!newVal.equals(oldVal)) {
                    changes.add(new SecretChange(key, SecretChange.ChangeType.MODIFIED, oldVal, newVal));
                }
            }
        }

        // Removed
        for (String key : oldSecrets.keySet()) {
            if (!newSecrets.containsKey(key)) {
                changes.add(new SecretChange(key, SecretChange.ChangeType.REMOVED, oldSecrets.get(key), null));
            }
        }

        return changes;
    }

    private void notifyListeners(List<SecretChange> changes) {
        List<SecretChange> immutable = Collections.unmodifiableList(changes);
        for (SecretsChangedListener listener : listeners) {
            try {
                listener.onSecretsChanged(immutable);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "bella-poller: listener threw exception", e);
            }
        }
    }

    @Override
    public void close() {
        if (pollingTask != null) {
            pollingTask.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
