package io.bellabaxter.spring;

import io.bellabaxter.BaxterClient;
import io.bellabaxter.BaxterClientOptions;
import io.bellabaxter.BellaPollingProvider;
import io.bellabaxter.SecretChange;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spring {@link MapPropertySource} backed by Bella Baxter secrets.
 *
 * <p>Registers itself at the front of the Spring {@link ConfigurableEnvironment} so that
 * Bella Baxter secrets override application.properties / application.yml values.
 *
 * <p>When a {@link BellaPollingProvider} is supplied and polling is enabled, this source
 * updates its internal map on each change and optionally triggers a context refresh so
 * that {@code @RefreshScope} beans pick up the new values.
 *
 * <p>Instantiated automatically via {@link BellaPollingAutoConfiguration}.
 */
public class BellaSecretsPropertySource extends MapPropertySource {

    public static final String PROPERTY_SOURCE_NAME = "bellabaxter";

    private final BellaPollingProvider poller;
    private Runnable onChangeCallback;

    /**
     * Create a property source backed by a polling provider.
     *
     * @param poller the polling provider that supplies (and updates) secrets
     */
    public BellaSecretsPropertySource(BellaPollingProvider poller) {
        super(PROPERTY_SOURCE_NAME, new HashMap<>(poller.getSecrets()));
        this.poller = poller;
        this.poller.addListener(this::applyChanges);
    }

    /**
     * Create a one-shot (non-polling) property source.
     *
     * @param client  the Bella Baxter client
     */
    public BellaSecretsPropertySource(BaxterClient client) {
        super(PROPERTY_SOURCE_NAME, new HashMap<>(client.getAllSecrets().getSecrets()));
        this.poller = null;
    }

    /**
     * Register a callback to invoke after the secrets map is updated.
     * Used by {@link BellaPollingAutoConfiguration} to wire in {@code ContextRefresher}.
     */
    public void setOnChangeCallback(Runnable callback) {
        this.onChangeCallback = callback;
    }

    /** @return the underlying {@link BellaPollingProvider}, or {@code null} if polling is disabled. */
    public BellaPollingProvider getPoller() {
        return poller;
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void applyChanges(List<SecretChange> changes) {
        Map<String, Object> source = (Map<String, Object>) getSource();
        for (SecretChange change : changes) {
            switch (change.changeType()) {
                case ADDED, MODIFIED -> source.put(change.key(), change.newValue());
                case REMOVED         -> source.remove(change.key());
            }
        }
        if (onChangeCallback != null) {
            onChangeCallback.run();
        }
    }
}
