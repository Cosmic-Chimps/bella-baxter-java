package io.bellabaxter.samples;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Application-scoped store for Bella Baxter secrets.
 *
 * Populated once at startup by BellaStartup.
 * Injected anywhere via @Inject BellaSecretsStore.
 */
@ApplicationScoped
public class BellaSecretsStore {

    private Map<String, String> secrets = Collections.emptyMap();

    void init(Map<String, String> loaded) {
        this.secrets = new HashMap<>(loaded);
    }

    /** Returns a secret by key, or empty if not found. */
    public Optional<String> get(String key) {
        return Optional.ofNullable(secrets.get(key));
    }

    /** Returns all loaded secrets (read-only). */
    public Map<String, String> all() {
        return Collections.unmodifiableMap(secrets);
    }
}
