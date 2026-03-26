package io.bellabaxter.samples;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Typed accessor for Bella Baxter secrets in Quarkus.
 *
 * Quarkus @ConfigMapping only works for build-time / MicroProfile Config sources.
 * Since Bella secrets are loaded at runtime (via BellaStartup), we wrap
 * BellaSecretsStore in an @ApplicationScoped bean that exposes typed methods.
 *
 * Inject anywhere with:
 *   @Inject BellaAppSecrets secrets;
 *   secrets.port()
 *   secrets.connectionStringsPostgres()
 */
@ApplicationScoped
public class BellaAppSecrets {

    @Inject
    BellaSecretsStore store;

    public String port()                    { return store.get("PORT").orElse("(not set)"); }
    public String databaseUrl()             { return store.get("DATABASE_URL").orElse("(not set)"); }
    public String externalApiKey()          { return store.get("EXTERNAL_API_KEY").orElse("(not set)"); }
    public String gleapApiKey()             { return store.get("GLEAP_API_KEY").orElse("(not set)"); }
    public String enableFeatures()          { return store.get("ENABLE_FEATURES").orElse("(not set)"); }
    public String appId()                   { return store.get("APP_ID").orElse("(not set)"); }
    public String appConfig()               { return store.get("APP_CONFIG").orElse("(not set)"); }
    public String connectionStringsPostgres() { return store.get("ConnectionStrings__Postgres").orElse("(not set)"); }
}
