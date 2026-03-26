package io.bellabaxter.samples;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Typed accessor for Bella Baxter secrets in Spring Boot.
 *
 * Populated automatically by Spring Boot's relaxed binding from the
 * "bellabaxter.*" property namespace added by BellaEnvironmentPostProcessor.
 *
 * Inject anywhere with:
 *   @Autowired BellaAppSecrets secrets;
 *   secrets.getPort()
 *   secrets.getConnectionStrings().getPostgres()
 */
@Component
@ConfigurationProperties(prefix = "bellabaxter")
public class BellaAppSecrets {

    // Spring Boot relaxed binding maps:
    //   bellabaxter.PORT           → port
    //   bellabaxter.DATABASE_URL   → databaseUrl
    //   bellabaxter.EXTERNAL_API_KEY → externalApiKey
    //   bellabaxter.ConnectionStrings__Postgres → connectionStrings.postgres  (__ = nested)

    private String port;
    private String databaseUrl;
    private String externalApiKey;
    private String gleapApiKey;
    private String enableFeatures;
    private String appId;
    private String appConfig;
    private ConnectionStrings connectionStrings = new ConnectionStrings();

    public static class ConnectionStrings {
        private String postgres;
        public String getPostgres() { return postgres; }
        public void setPostgres(String postgres) { this.postgres = postgres; }
    }

    public String getPort() { return port; }
    public void setPort(String port) { this.port = port; }

    public String getDatabaseUrl() { return databaseUrl; }
    public void setDatabaseUrl(String databaseUrl) { this.databaseUrl = databaseUrl; }

    public String getExternalApiKey() { return externalApiKey; }
    public void setExternalApiKey(String externalApiKey) { this.externalApiKey = externalApiKey; }

    public String getGleapApiKey() { return gleapApiKey; }
    public void setGleapApiKey(String gleapApiKey) { this.gleapApiKey = gleapApiKey; }

    public String getEnableFeatures() { return enableFeatures; }
    public void setEnableFeatures(String enableFeatures) { this.enableFeatures = enableFeatures; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getAppConfig() { return appConfig; }
    public void setAppConfig(String appConfig) { this.appConfig = appConfig; }

    public ConnectionStrings getConnectionStrings() { return connectionStrings; }
    public void setConnectionStrings(ConnectionStrings connectionStrings) { this.connectionStrings = connectionStrings; }
}
