package io.bellabaxter.model;

import java.util.Map;

/** Response from {@code GET /api/v1/environments/{slug}/secrets}. */
public final class AllEnvironmentSecretsResponse {

    private final String environmentSlug;
    private final String environmentName;
    /**
     * All secrets for the environment, aggregated from all assigned providers.
     * Served from Baxter's Redis cache — does NOT hit AWS/Azure/GCP per call.
     */
    private final Map<String, String> secrets;
    /** Monotonically increasing version (unix seconds of last mutation). */
    private final long version;
    /** ISO-8601 timestamp of last mutation. */
    private final String lastModified;

    public AllEnvironmentSecretsResponse(
            String environmentSlug,
            String environmentName,
            Map<String, String> secrets,
            long version,
            String lastModified) {
        this.environmentSlug = environmentSlug;
        this.environmentName = environmentName;
        this.secrets = secrets;
        this.version = version;
        this.lastModified = lastModified;
    }

    public String              getEnvironmentSlug() { return environmentSlug; }
    public String              getEnvironmentName() { return environmentName; }
    public Map<String, String> getSecrets()         { return secrets; }
    public long                getVersion()         { return version; }
    public String              getLastModified()    { return lastModified; }
}
