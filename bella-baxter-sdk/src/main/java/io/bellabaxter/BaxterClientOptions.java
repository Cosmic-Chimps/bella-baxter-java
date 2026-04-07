package io.bellabaxter;

import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * Options for constructing a {@link BaxterClient}.
 *
 * <pre>{@code
 * BaxterClientOptions opts = new BaxterClientOptions.Builder()
 *     .baxterUrl("https://api.bella-baxter.io")
 *     .apiKey("bax-...")
 *     .build();
 * }</pre>
 */
public final class BaxterClientOptions {

    public static final String DEFAULT_URL = "https://api.bella-baxter.io";

    private final String baxterUrl;
    private final String apiKey;
    private final int timeoutSeconds;
    private final boolean pollingEnabled;
    private final Duration pollingInterval;
    private final boolean fallbackOnError;
    private final String privateKeyPem;
    private final BiConsumer<String, String> onWrappedDekReceived;

    private BaxterClientOptions(Builder builder) {
        this.baxterUrl      = (builder.baxterUrl != null ? builder.baxterUrl : DEFAULT_URL).replaceAll("/$", "");
        this.apiKey         = requireNonBlank(builder.apiKey, "apiKey");
        this.timeoutSeconds = builder.timeoutSeconds > 0 ? builder.timeoutSeconds : 10;
        this.pollingEnabled = builder.pollingEnabled;
        this.pollingInterval = builder.pollingInterval != null ? builder.pollingInterval : Duration.ofSeconds(60);
        this.fallbackOnError = builder.fallbackOnError;
        this.privateKeyPem         = builder.privateKeyPem;
        this.onWrappedDekReceived  = builder.onWrappedDekReceived;
    }

    public String   getBaxterUrl()      { return baxterUrl; }
    public String   getApiKey()         { return apiKey; }
    public int      getTimeoutSeconds() { return timeoutSeconds; }
    public boolean  isPollingEnabled()  { return pollingEnabled; }
    public Duration getPollingInterval(){ return pollingInterval; }
    public boolean  isFallbackOnError() { return fallbackOnError; }
        /** PKCS#8 PEM private key for ZKE, or {@code null} if not configured. */
    public String getPrivateKeyPem() { return privateKeyPem; }

    /**
     * Listener invoked when the API returns an {@code X-Bella-Wrapped-Dek} header.
     * Arguments: {@code wrappedDek} (base64), {@code leaseExpires} (ISO-8601, may be null).
     */
    public BiConsumer<String, String> getOnWrappedDekReceived() { return onWrappedDekReceived; }


    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public static final class Builder {
        private String baxterUrl;
        private String apiKey;
        private int timeoutSeconds = 10;
        private boolean pollingEnabled = false;
        private Duration pollingInterval = Duration.ofSeconds(60);
        private boolean fallbackOnError = true;
        private String privateKeyPem;
        private BiConsumer<String, String> onWrappedDekReceived;

        /** Base URL of the Baxter API, e.g. {@code https://baxter.example.com}. */
        public Builder baxterUrl(String baxterUrl)         { this.baxterUrl = baxterUrl; return this; }
        /** Bella Baxter API key (bax-...). Obtain via WebApp or: bella apikeys create. */
        public Builder apiKey(String apiKey)               { this.apiKey = apiKey; return this; }
        /** HTTP timeout in seconds (default: 10). */
        public Builder timeoutSeconds(int timeoutSeconds)  { this.timeoutSeconds = timeoutSeconds; return this; }
        /**
         * Enable background polling for secret changes (default: false).
         * When enabled, a {@link BellaPollingProvider} can be created from this client
         * to watch for changes and notify listeners.
         */
        public Builder pollingEnabled(boolean pollingEnabled) { this.pollingEnabled = pollingEnabled; return this; }
        /**
         * How often to check for secret changes (default: 60 seconds).
         * Only relevant when pollingEnabled is true.
         */
        public Builder pollingInterval(Duration pollingInterval) { this.pollingInterval = pollingInterval; return this; }
        /**
         * If true (default), return cached secrets on API failure instead of throwing.
         * Only relevant when pollingEnabled is true.
         */
        public Builder fallbackOnError(boolean fallbackOnError) { this.fallbackOnError = fallbackOnError; return this; }

        /**
         * PKCS#8 PEM private key for ZKE (Zero-Knowledge Encryption).
         * Obtain with: {@code bella auth setup}.
         * Falls back to the {@code BELLA_BAXTER_PRIVATE_KEY} environment variable when not set here.
         */
        public Builder privateKeyPem(String privateKeyPem) {
            this.privateKeyPem = privateKeyPem;
            return this;
        }

        /**
         * Callback invoked whenever the API returns an {@code X-Bella-Wrapped-Dek} response header.
         *
         * @param listener receives {@code (wrappedDek, leaseExpires)} — both are base64/ISO-8601
         *                 strings; {@code leaseExpires} may be {@code null}
         */
        public Builder onWrappedDekReceived(BiConsumer<String, String> listener) {
            this.onWrappedDekReceived = listener;
            return this;
        }

        public BaxterClientOptions build() { return new BaxterClientOptions(this); }
    }
}
