package io.bellabaxter;

import java.time.Duration;

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

    private BaxterClientOptions(Builder builder) {
        this.baxterUrl      = (builder.baxterUrl != null ? builder.baxterUrl : DEFAULT_URL).replaceAll("/$", "");
        this.apiKey         = requireNonBlank(builder.apiKey, "apiKey");
        this.timeoutSeconds = builder.timeoutSeconds > 0 ? builder.timeoutSeconds : 10;
        this.pollingEnabled = builder.pollingEnabled;
        this.pollingInterval = builder.pollingInterval != null ? builder.pollingInterval : Duration.ofSeconds(60);
        this.fallbackOnError = builder.fallbackOnError;
    }

    public String   getBaxterUrl()      { return baxterUrl; }
    public String   getApiKey()         { return apiKey; }
    public int      getTimeoutSeconds() { return timeoutSeconds; }
    public boolean  isPollingEnabled()  { return pollingEnabled; }
    public Duration getPollingInterval(){ return pollingInterval; }
    public boolean  isFallbackOnError() { return fallbackOnError; }

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

        public BaxterClientOptions build() { return new BaxterClientOptions(this); }
    }
}
