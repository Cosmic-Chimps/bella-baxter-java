package io.bellabaxter.model;

/** Response from {@code GET /api/v1/environments/{slug}/secrets/version}. */
public final class EnvironmentSecretsVersionResponse {

    private final String environmentSlug;
    private final long   version;
    private final String lastModified;

    public EnvironmentSecretsVersionResponse(String environmentSlug, long version, String lastModified) {
        this.environmentSlug = environmentSlug;
        this.version         = version;
        this.lastModified    = lastModified;
    }

    public String getEnvironmentSlug() { return environmentSlug; }
    public long   getVersion()         { return version; }
    public String getLastModified()    { return lastModified; }
}
