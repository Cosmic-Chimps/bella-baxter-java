package io.bellabaxter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.kiota.http.OkHttpRequestAdapter;
import io.bellabaxter.generated.BellaClient;
import io.bellabaxter.generated.models.AllEnvironmentSecretsResponse;
import io.bellabaxter.generated.models.EnvironmentSecretsVersionResponse;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe Bella Baxter API client backed by the Kiota-generated {@link BellaClient}.
 *
 * <p>Instantiate once and reuse.
 *
 * <pre>{@code
 * BaxterClient client = new BaxterClient(new BaxterClientOptions.Builder()
 *     .baxterUrl("https://baxter.example.com")
 *     .apiKey("bax-...")
 *     .build());
 *
 * var resp = client.getAllSecrets();
 * String dbUrl = resp.getSecrets().get("DATABASE_URL");
 * }</pre>
 */
public final class BaxterClient implements AutoCloseable {

    private final BellaClient kiota;
    private final OkHttpClient okHttp;
    private final String baseUrl;
    private final String keyId;
    private final byte[] signingSecret;
    private volatile KeyContext keyContext;

    public BaxterClient(BaxterClientOptions options) {
        HmacAuthenticationProvider auth = new HmacAuthenticationProvider(options.getApiKey());

        // Parse key parts for raw HTTP signing
        String[] parts = options.getApiKey().split("-", 3);
        this.keyId = parts[1];
        this.signingSecret = HexFormat.of().parseHex(parts[2]);

        OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder()
                .connectTimeout(options.getTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(options.getTimeoutSeconds(), TimeUnit.SECONDS);
        httpBuilder.addInterceptor(chain -> chain.proceed(
                chain.request().newBuilder()
                        .header("User-Agent", "bella-java-sdk/1.0")
                        .header("X-Bella-Client", "bella-java-sdk")
                        .build()));

        // ZKE: prefer explicit privateKeyPem option, then fall back to env var
        String privateKeyPem = options.getPrivateKeyPem();
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            privateKeyPem = System.getenv("BELLA_BAXTER_PRIVATE_KEY");
        }

        E2EEncryptionInterceptor e2eeInterceptor;
        if (privateKeyPem != null && !privateKeyPem.isBlank()) {
            try {
                E2EEncryption e2ee = E2EEncryption.fromPkcs8Pem(privateKeyPem);
                e2eeInterceptor = new E2EEncryptionInterceptor(e2ee, options.getOnWrappedDekReceived());
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("Invalid ZKE private key: " + e.getMessage(), e);
            }
        } else {
            e2eeInterceptor = new E2EEncryptionInterceptor();
        }
        httpBuilder.addInterceptor(e2eeInterceptor);
        this.okHttp  = httpBuilder.build();
        this.baseUrl = options.getBaxterUrl().replaceAll("/$", "");

        OkHttpRequestAdapter adapter = new OkHttpRequestAdapter(auth, null, null, okHttp);
        adapter.setBaseUrl(this.baseUrl);
        this.kiota = new BellaClient(adapter);
    }

    // ── Key context ────────────────────────────────────────────────────────────

    /**
     * Response from GET /api/v1/keys/me — describes the project + environment
     * this API key is scoped to.
     */
    public static final class KeyContext {
        public final String keyId;
        public final String role;
        public final String projectSlug;
        public final String projectName;
        public final String environmentSlug;
        public final String environmentName;

        public KeyContext(String keyId, String role,
                          String projectSlug, String projectName,
                          String environmentSlug, String environmentName) {
            this.keyId = keyId; this.role = role;
            this.projectSlug = projectSlug; this.projectName = projectName;
            this.environmentSlug = environmentSlug; this.environmentName = environmentName;
        }
    }

    /**
     * Calls GET /api/v1/keys/me to discover the project + environment this API key
     * is scoped to. Result is cached after the first call.
     */
    public synchronized KeyContext getKeyContext() {
        if (keyContext != null) return keyContext;
        try {
            String path      = "/api/v1/keys/me";
            String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                    .replaceAll("\\.\\d+Z$", "Z");
            byte[] emptyBody = new byte[0];
            String bodyHash  = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(emptyBody));
            String sts = "GET\n" + path + "\n\n" + timestamp + "\n" + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            String sig = HexFormat.of().formatHex(
                    mac.doFinal(sts.getBytes(StandardCharsets.UTF_8)));

            Request req = new Request.Builder()
                    .url(baseUrl + path)
                    .addHeader("Accept", "application/json")
                    .addHeader("X-Bella-Key-Id", keyId)
                    .addHeader("X-Bella-Timestamp", timestamp)
                    .addHeader("X-Bella-Signature", sig)
                    .get()
                    .build();

            try (Response resp = okHttp.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    throw new BaxterException("getKeyContext: HTTP " + resp.code());
                }
                String body = resp.body() != null ? resp.body().string() : "{}";
                @SuppressWarnings("unchecked")
                Map<String, Object> json = new ObjectMapper().readValue(body, Map.class);
                keyContext = new KeyContext(
                        str(json, "keyId"),
                        str(json, "role"),
                        str(json, "projectSlug"),
                        str(json, "projectName"),
                        str(json, "environmentSlug"),
                        str(json, "environmentName")
                );
            }
        } catch (BaxterException e) {
            throw e;
        } catch (Exception e) {
            throw new BaxterException("getKeyContext failed: " + e.getMessage(), e);
        }
        return keyContext;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v != null ? v.toString() : "";
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Fetch all secrets for the environment derived from this API key.
     * Project and environment are auto-discovered via GET /api/v1/keys/me.
     */
    public io.bellabaxter.model.AllEnvironmentSecretsResponse getAllSecrets() {
        KeyContext ctx = getKeyContext();
        return getAllSecrets(ctx.projectSlug, ctx.environmentSlug);
    }

    /**
     * Fetch all secrets for an environment across all assigned providers.
     *
     * @param projectRef      project GUID or slug (e.g. "my-app" or "4f8a8f9a-...")
     * @param environmentSlug the environment slug (e.g. "production")
     */
    public io.bellabaxter.model.AllEnvironmentSecretsResponse getAllSecrets(
            String projectRef, String environmentSlug) {

        AllEnvironmentSecretsResponse resp = kiota.api().v1().projects()
                .byId(projectRef)
                .environments().byEnvSlug(environmentSlug)
                .secrets().get();

        Map<String, String> secrets = new HashMap<>();
        if (resp.getSecrets() != null && resp.getSecrets().getAdditionalData() != null) {
            for (Map.Entry<String, Object> e : resp.getSecrets().getAdditionalData().entrySet()) {
                if (e.getValue() instanceof String s) secrets.put(e.getKey(), s);
            }
        }
        return new io.bellabaxter.model.AllEnvironmentSecretsResponse(
                resp.getEnvironmentSlug(), resp.getEnvironmentName(), secrets,
                resp.getVersion() != null ? resp.getVersion() : 0L,
                resp.getLastModified() != null ? resp.getLastModified().toString() : "");
    }

    /**
     * Lightweight version check. Project/environment auto-discovered from the API key.
     */
    public io.bellabaxter.model.EnvironmentSecretsVersionResponse getSecretsVersion() {
        KeyContext ctx = getKeyContext();
        return getSecretsVersion(ctx.projectSlug, ctx.environmentSlug);
    }

    /**
     * Lightweight version check — returns version + lastModified only.
     *
     * @param projectRef      project GUID or slug
     * @param environmentSlug the environment slug
     */
    public io.bellabaxter.model.EnvironmentSecretsVersionResponse getSecretsVersion(
            String projectRef, String environmentSlug) {

        EnvironmentSecretsVersionResponse resp = kiota.api().v1().projects()
                .byId(projectRef)
                .environments().byEnvSlug(environmentSlug)
                .secrets().version().get();

        return new io.bellabaxter.model.EnvironmentSecretsVersionResponse(
                resp.getEnvironmentSlug(),
                resp.getVersion() != null ? resp.getVersion() : 0L,
                resp.getLastModified() != null ? resp.getLastModified().toString() : "");
    }

    /** The underlying Kiota client — use for full admin API access. */
    public BellaClient getClient() { return kiota; }

    @Override
    public void close() {}

    // ── Exceptions ─────────────────────────────────────────────────────────────

    public static class BaxterException extends RuntimeException {
        public BaxterException(String message) { super(message); }
        public BaxterException(String message, Throwable cause) { super(message, cause); }
    }

    public static class BaxterAuthException extends BaxterException {
        public BaxterAuthException(String message) { super(message); }
    }

    public static class BaxterNotFoundException extends BaxterException {
        public BaxterNotFoundException(String message) { super(message); }
    }
}
