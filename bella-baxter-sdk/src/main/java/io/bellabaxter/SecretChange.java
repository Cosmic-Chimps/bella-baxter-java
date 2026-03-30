package io.bellabaxter;

/**
 * Describes a single secret change detected by {@link BellaPollingProvider}.
 *
 * @param key        the secret key (e.g. {@code DATABASE_URL})
 * @param changeType the type of change
 * @param oldValue   the previous value, or {@code null} if added
 * @param newValue   the new value, or {@code null} if removed
 */
public record SecretChange(
        String key,
        ChangeType changeType,
        String oldValue,
        String newValue
) {
    public enum ChangeType {
        /** Secret was added (did not previously exist). */
        ADDED,
        /** Secret value was modified. */
        MODIFIED,
        /** Secret was removed. */
        REMOVED
    }
}
