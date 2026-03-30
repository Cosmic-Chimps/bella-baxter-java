package io.bellabaxter;

import java.util.List;

/**
 * Callback interface invoked when Bella Baxter secret values change.
 *
 * <p>Registered on a {@link BellaPollingProvider} via
 * {@link BellaPollingProvider#addListener(SecretsChangedListener)}.
 *
 * <pre>{@code
 * provider.addListener(changes -> {
 *     for (SecretChange change : changes) {
 *         System.out.printf("[%s] %s changed%n", change.changeType(), change.key());
 *     }
 * });
 * }</pre>
 */
@FunctionalInterface
public interface SecretsChangedListener {

    /**
     * Called on the polling thread when one or more secrets have changed.
     *
     * @param changes list of changes detected in this polling cycle
     */
    void onSecretsChanged(List<SecretChange> changes);
}
