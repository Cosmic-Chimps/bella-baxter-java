package io.bellabaxter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.kiota.RequestInformation;
import com.sun.net.httpserver.HttpServer;
import io.bellabaxter.generated.api.v1.projects.item.environments.item.WithEnvSlugItemRequestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1162 — the key is presented on EVERY envelope-required read (apps/sdk/SDK_CONTRACT.md, "Rule: the key is
 * presented on every envelope-required read"), and the decrypted body reaches the caller UNCHANGED. Before the
 * fix the interceptor presented only on GETs ending in {@code /secrets}, so {@code getSecret},
 * {@code getSecretVersion} and both exports went out without the key, and a decrypted body without a
 * {@code secrets} object was rebuilt into one.
 *
 * <p>Each read goes through the real {@link BaxterClient} → generated builder → request adapter → interceptor,
 * and is read as RAW bytes via {@link BaxterClient#getRequestAdapter()}.
 */
class E2EEKeyPresentationTest {

    private static final String P = "contract-project";
    private static final String E = "contract-env";
    private static final String V = "contract-provider";
    private static final String K = "BELLA_KEY_CONTRACT";
    private static final String S = "the-presented-key-decrypted-this";
    private static final String ITEM = "{\"key\":\"" + K + "\",\"value\":\"" + S + "\",\"description\":null}";

    private static final String ENV = "/api/v1/projects/" + P + "/environments/" + E;
    private static final String PROV = ENV + "/providers/" + V + "/secrets";

    /** Path → plaintext the API encrypts for it (the shapes in SDK_CONTRACT.md's table). */
    private static final Map<String, String> READS = Map.of(
            ENV + "/secrets", "{\"environmentSlug\":\"" + E + "\",\"environmentName\":\"" + E + "\",\"secrets\":{\""
                    + K + "\":\"" + S + "\"},\"version\":1,\"lastModified\":\"2026-10-04T00:00:00Z\"}",
            ENV + "/secrets/export", "{\"" + K + "\":\"" + S + "\"}",
            PROV, "[" + ITEM + "]",
            PROV + "/export", "{\"" + K + "\":\"" + S + "\"}",
            PROV + "/" + K, ITEM,
            PROV + "/" + K + "/versions/1", ITEM,
            "/api/v1/projects/" + P + "/secrets",
            "{\"projectRef\":\"" + P + "\",\"projectSlug\":\"" + P + "\",\"globalSecretProviderId\":null,\"secrets\":[" + ITEM + "]}");

    private HttpServer server;
    private final Map<String, String> presented = new ConcurrentHashMap<>(); // path → key or "-"
    private final ObjectMapper mapper = new ObjectMapper();
    private BaxterClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String key = exchange.getRequestHeaders().getFirst("X-E2E-Public-Key");
            presented.put(path, key == null ? "-" : key);
            int status = 200;
            String body;
            try {
                String plain = READS.get(path);
                if (path.equals(ENV + "/secrets/version")) {
                    body = "{\"environmentSlug\":\"" + E + "\",\"version\":7,\"lastModified\":\"2026-10-04T00:00:00Z\"}";
                } else if (plain == null) {
                    status = 404;
                    body = "{}";
                } else if (key == null) {
                    status = 403; // what a ZKE-enforcing tenant answers a read without the device key
                    body = "{\"type\":\"zke-key-required\"}";
                } else {
                    body = E2EEResponseContractTest.encryptFor(key, plain, false);
                }
            } catch (Exception e) {
                status = 500;
                body = "{\"error\":\"stub\"}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new BaxterClient(new BaxterClientOptions.Builder()
                .baxterUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .bearerToken("contract-token")
                .projectSlug(P)
                .environmentSlug(E)
                .build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private WithEnvSlugItemRequestBuilder env() {
        return client.getClient().api().v1().projects().byId(P).environments().byEnvSlug(E);
    }

    /** operationId → the request the generated client builds for it. */
    private Map<String, RequestInformation> requests() {
        return Map.of(
                "getAllEnvironmentSecrets", env().secrets().toGetRequestInformation(),
                "exportEnvironmentSecrets", env().secrets().export().toGetRequestInformation(c -> c.queryParameters.format = "json"),
                "listSecrets", env().providers().byProviderSlug(V).secrets().toGetRequestInformation(),
                "exportSecrets", env().providers().byProviderSlug(V).secrets().export()
                        .toGetRequestInformation(c -> c.queryParameters.format = "json"),
                "getSecret", env().providers().byProviderSlug(V).secrets().byKey(K).toGetRequestInformation(),
                "getSecretVersion", env().providers().byProviderSlug(V).secrets().byKey(K).versions().byVersion(1)
                        .toGetRequestInformation(),
                "listGlobalSecrets", client.getClient().api().v1().projects().byId(P).secrets().toGetRequestInformation());
    }

    private JsonNode raw(RequestInformation info) throws Exception {
        try (InputStream in = client.getRequestAdapter().sendPrimitive(info, null, InputStream.class)) {
            return mapper.readTree(in.readAllBytes());
        }
    }

    @Test
    void everyEnvelopeRequiredRead_presentsTheKey_andHandsOnTheDecryptedBodyUnchanged() throws Exception {
        Map<String, Function<JsonNode, Boolean>> probes = Map.of(
                "getAllEnvironmentSecrets", b -> S.equals(b.path("secrets").path(K).asText(null)),
                "exportEnvironmentSecrets", b -> S.equals(b.path(K).asText(null)) && b.size() == 1,
                "listSecrets", b -> b.isArray() && K.equals(b.path(0).path("key").asText(null))
                        && S.equals(b.path(0).path("value").asText(null)),
                "exportSecrets", b -> S.equals(b.path(K).asText(null)) && b.size() == 1,
                "getSecret", b -> K.equals(b.path("key").asText(null)) && S.equals(b.path("value").asText(null))
                        && !b.has("secrets"),
                "getSecretVersion", b -> K.equals(b.path("key").asText(null)) && S.equals(b.path("value").asText(null))
                        && !b.has("secrets"),
                "listGlobalSecrets", b -> S.equals(b.path("secrets").path(0).path("value").asText(null)));

        for (var r : requests().entrySet()) {
            JsonNode body = raw(r.getValue());
            assertTrue(probes.get(r.getKey()).apply(body), r.getKey() + " decrypted to the wrong document: " + body);
            // byte-for-byte the plaintext the server encrypted (as JSON)
            String path = r.getValue().getUri().getPath();
            assertEquals(mapper.readTree(READS.get(path)), body, r.getKey());
        }
        for (String path : READS.keySet()) {
            String key = presented.get(path);
            assertNotNull(key, "never called: " + path);
            assertNotEquals("-", key, "key not presented on " + path);
        }
    }

    @Test
    void theKeyIsNotPresented_onAReadThatCarriesNoValue() {
        var version = client.getSecretsVersion(P, E);
        assertEquals(7L, version.getVersion());
        assertEquals("-", presented.get(ENV + "/secrets/version"));
    }

    @Test
    void presentationFollowsTheMatcher_includingAnExportWithAQueryString() {
        // The interceptor decides on the encoded PATH; a query string must not hide an export from it.
        assertTrue(E2EEncryptionInterceptor.requiresEnvelope("GET", "/api/v1/projects/p/environments/e/secrets/export"));
        assertFalse(E2EEncryptionInterceptor.requiresEnvelope("GET", "/api/v1/projects/p/environments/e/secrets/version"));
        assertFalse(E2EEncryptionInterceptor.requiresEnvelope("POST", "/api/v1/projects/p/environments/e/providers/v/secrets"));
    }
}
