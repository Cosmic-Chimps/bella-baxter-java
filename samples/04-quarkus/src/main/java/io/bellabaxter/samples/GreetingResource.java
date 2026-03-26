package io.bellabaxter.samples;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class GreetingResource {

    @Inject
    BellaSecretsStore secrets;

    @Inject
    BellaAppSecrets appSecrets;

    @GET
    public Map<String, String> index() {
        String[] keys = {
            "PORT", "DATABASE_URL", "EXTERNAL_API_KEY", "GLEAP_API_KEY",
            "ENABLE_FEATURES", "APP_ID", "ConnectionStrings__Postgres", "APP_CONFIG"
        };
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String key : keys) {
            result.put(key, secrets.get(key).orElse("(not set)"));
        }
        return result;
    }

    @GET
    @Path("/health")
    public Map<String, Boolean> health() {
        return Map.of("ok", true);
    }

    /** Typed access via @ApplicationScoped BellaAppSecrets wrapper */
    @GET
    @Path("/typed")
    public Map<String, String> typed() {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        result.put("PORT", appSecrets.port());
        result.put("DATABASE_URL", appSecrets.databaseUrl());
        result.put("EXTERNAL_API_KEY", appSecrets.externalApiKey());
        result.put("GLEAP_API_KEY", appSecrets.gleapApiKey());
        result.put("ENABLE_FEATURES", appSecrets.enableFeatures());
        result.put("APP_ID", appSecrets.appId());
        result.put("ConnectionStrings__Postgres", appSecrets.connectionStringsPostgres());
        result.put("APP_CONFIG", appSecrets.appConfig());
        return result;
    }
}
