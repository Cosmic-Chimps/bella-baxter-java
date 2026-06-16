package io.bellabaxter.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for Bella Baxter Spring Boot integration.
 *
 * <p>All properties are prefixed with {@code bellabaxter}.
 */
@ConfigurationProperties(prefix = "bellabaxter")
public class BellaProperties {

    /** Base URL of the Baxter API. Defaults to {@code https://api.bella-baxter.io}. */
    private String url = "https://api.bella-baxter.io";

    /** API key (bax-...). Required for HMAC mode. */
    private String apiKey;

    /** OAuth2 JWT access token. Alternative to apiKey for Bearer mode (injected by bella sdk run). */
    private String accessToken;

    /** Project slug — required when using accessToken. Maps to BELLA_BAXTER_PROJECT env var. */
    private String project;

    /** Environment slug — required when using accessToken. Maps to BELLA_BAXTER_ENV env var. */
    private String environment;

    /** HTTP timeout in seconds (default: 10). */
    private int timeoutSeconds = 10;

    private Polling polling = new Polling();

    public String  getUrl()            { return url; }
    public void    setUrl(String url)  { this.url = url; }

    public String  getApiKey()              { return apiKey; }
    public void    setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String  getAccessToken()                    { return accessToken; }
    public void    setAccessToken(String accessToken)  { this.accessToken = accessToken; }

    public String  getProject()               { return project; }
    public void    setProject(String project) { this.project = project; }

    public String  getEnvironment()                    { return environment; }
    public void    setEnvironment(String environment)  { this.environment = environment; }

    public int     getTimeoutSeconds()                  { return timeoutSeconds; }
    public void    setTimeoutSeconds(int timeoutSeconds){ this.timeoutSeconds = timeoutSeconds; }

    public Polling getPolling()              { return polling; }
    public void    setPolling(Polling p)     { this.polling = p; }

    public static class Polling {
        /** Enable background polling for secret changes (default: false). */
        private boolean enabled = false;

        /** How often to poll for changes (default: 60 seconds). */
        private Duration interval = Duration.ofSeconds(60);

        /** Return cached secrets on API failure instead of throwing (default: true). */
        private boolean fallbackOnError = true;

        public boolean  isEnabled()                  { return enabled; }
        public void     setEnabled(boolean enabled)  { this.enabled = enabled; }

        public Duration getInterval()                { return interval; }
        public void     setInterval(Duration i)      { this.interval = i; }

        public boolean  isFallbackOnError()                      { return fallbackOnError; }
        public void     setFallbackOnError(boolean fallbackOnError){ this.fallbackOnError = fallbackOnError; }
    }
}
