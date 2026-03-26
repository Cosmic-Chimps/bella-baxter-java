package io.bellabaxter;

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

    private BaxterClientOptions(Builder builder) {
        this.baxterUrl = (builder.baxterUrl != null ? builder.baxterUrl : DEFAULT_URL).replaceAll("/$", "");
        this.apiKey = requireNonBlank(builder.apiKey, "apiKey");
        this.timeoutSeconds = builder.timeoutSeconds > 0 ? builder.timeoutSeconds : 10;
    }

    public String getBaxterUrl()     { return baxterUrl; }
    public String getApiKey()        { return apiKey; }
    public int    getTimeoutSeconds(){ return timeoutSeconds; }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    public static final class Builder {
        private String baxterUrl;
        private String apiKey;
        private int timeoutSeconds = 10;

        /** Base URL of the Baxter API, e.g. {@code https://baxter.example.com}. */
        public Builder baxterUrl(String baxterUrl)         { this.baxterUrl = baxterUrl; return this; }
        /** Bella Baxter API key (bax-...). Obtain via WebApp or: bella apikeys create. */
        public Builder apiKey(String apiKey)               { this.apiKey = apiKey; return this; }
        /** HTTP timeout in seconds (default: 10). */
        public Builder timeoutSeconds(int timeoutSeconds)  { this.timeoutSeconds = timeoutSeconds; return this; }

        public BaxterClientOptions build() { return new BaxterClientOptions(this); }
    }
}
