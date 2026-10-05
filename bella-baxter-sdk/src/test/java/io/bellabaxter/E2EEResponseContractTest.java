package io.bellabaxter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #1050 (b) — once the SDK has presented its E2EE key, a secrets answer that is not a decryptable envelope is
 * refused (apps/sdk/SDK_CONTRACT.md, "Rule: a presented key requires an envelope"). A misbehaving stub serves
 * each case through the real {@link BaxterClient} → Kiota → OkHttp → interceptor pipeline.
 */
class E2EEResponseContractTest {

    private static final String PROJECT = "contract-project";
    private static final String ENV = "contract-env";
    private static final String SENTINEL_KEY = "BELLA_KEY_CONTRACT";
    private static final String SENTINEL_VALUE = "the-sentinel-must-never-be-returned";
    private static final String SECRETS_PATH = "/api/v1/projects/" + PROJECT + "/environments/" + ENV + "/secrets";

    enum Mode { VALID, PLAINTEXT, TAMPERED, WRONG_KEY, FORBIDDEN }

    private HttpServer server;
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.VALID);
    private final AtomicReference<String> presentedKey = new AtomicReference<>();
    private BaxterClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String key = exchange.getRequestHeaders().getFirst("X-E2E-Public-Key");
            int status = 200;
            String body;
            try {
                if (path.equals(SECRETS_PATH + "/version")) {
                    // Not an envelope-required read: answered in plain JSON even if a key were presented.
                    body = "{\"environmentSlug\":\"" + ENV + "\",\"version\":7,\"lastModified\":\"2026-10-04T00:00:00Z\"}";
                } else if (path.equals(SECRETS_PATH)) {
                    presentedKey.set(key);
                    String plain = "{\"environmentSlug\":\"" + ENV + "\",\"environmentName\":\"" + ENV + "\","
                            + "\"secrets\":{\"" + SENTINEL_KEY + "\":\"" + SENTINEL_VALUE + "\"},"
                            + "\"version\":1,\"lastModified\":\"2026-10-04T00:00:00Z\"}";
                    switch (mode.get()) {
                        case VALID -> body = encryptFor(key, plain, false);
                        case PLAINTEXT -> body = plain;
                        case TAMPERED -> body = encryptFor(key, plain, true);
                        case WRONG_KEY -> body = encryptFor(otherPublicKey(), plain, false);
                        default -> {
                            status = 403;
                            body = "{\"type\":\"zke-device-not-registered\",\"title\":\"forbidden\"}";
                        }
                    }
                } else {
                    status = 404;
                    body = "{}";
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
                .projectSlug(PROJECT)
                .environmentSlug(ENV)
                .build());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void validEnvelope_toThePresentedKey_isDecrypted() {
        mode.set(Mode.VALID);
        var resp = client.getAllSecrets(PROJECT, ENV);
        assertNotNull(presentedKey.get(), "the SDK must present X-E2E-Public-Key");
        assertEquals(SENTINEL_VALUE, resp.getSecrets().get(SENTINEL_KEY));
    }

    @Test
    void plaintext_afterPresentingTheKey_isRefused() {
        mode.set(Mode.PLAINTEXT);
        var ex = assertThrows(BaxterClient.BaxterException.class, () -> client.getAllSecrets(PROJECT, ENV));
        assertRefused(ex, E2EEResponseException.PLAINTEXT_RESPONSE,
                "E2EE response expected but plaintext received for " + SECRETS_PATH
                        + "; refusing it (e2ee-plaintext-response)");
        assertNotNull(presentedKey.get());
    }

    @Test
    void tamperedEnvelope_isRefused() {
        mode.set(Mode.TAMPERED);
        var ex = assertThrows(BaxterClient.BaxterException.class, () -> client.getAllSecrets(PROJECT, ENV));
        assertRefused(ex, E2EEResponseException.DECRYPTION_FAILED,
                "E2EE response could not be decrypted for " + SECRETS_PATH
                        + "; refusing it (e2ee-decryption-failed)");
    }

    @Test
    void envelopeToAnotherKey_isRefused() {
        mode.set(Mode.WRONG_KEY);
        var ex = assertThrows(BaxterClient.BaxterException.class, () -> client.getAllSecrets(PROJECT, ENV));
        assertRefused(ex, E2EEResponseException.DECRYPTION_FAILED,
                "E2EE response could not be decrypted for " + SECRETS_PATH
                        + "; refusing it (e2ee-decryption-failed)");
    }

    @Test
    void rawKiotaClient_alsoRefuses_withTheCodeInTheChain() {
        mode.set(Mode.PLAINTEXT);
        var ex = assertThrows(RuntimeException.class, () -> client.getClient().api().v1().projects()
                .byId(PROJECT).environments().byEnvSlug(ENV).secrets().get());
        var refused = E2EEResponseException.find(ex).orElseThrow(() -> new AssertionError("not refused: " + ex));
        assertEquals(E2EEResponseException.PLAINTEXT_RESPONSE, refused.getCode());
        assertTrue(ex.getMessage().contains("e2ee-plaintext-response"), ex.getMessage());
    }

    @Test
    void plainAnswer_onANonEnvelopeRead_stillPassesThrough() {
        var resp = client.getSecretsVersion(PROJECT, ENV);
        assertEquals(7L, resp.getVersion());
    }

    @Test
    void non2xx_isNotTurnedIntoAnE2EERefusal() {
        mode.set(Mode.FORBIDDEN);
        var ex = assertThrows(RuntimeException.class, () -> client.getAllSecrets(PROJECT, ENV));
        assertTrue(E2EEResponseException.find(ex).isEmpty(), "a 403 is an API error, not an E2EE refusal: " + ex);
    }

    @Test
    void decryptUtility_refusesAPlainBody() {
        var ex = assertThrows(E2EEResponseException.class, () -> new E2EEncryption()
                .decrypt(("{\"" + SENTINEL_KEY + "\":\"" + SENTINEL_VALUE + "\"}").getBytes(StandardCharsets.UTF_8)));
        assertEquals(E2EEResponseException.PLAINTEXT_RESPONSE, ex.getCode());
    }

    @Test
    void requiresEnvelope_matchesExactlyTheValueReads() {
        String e = "/api/v1/projects/p/environments/dev";
        for (String p : new String[] {
                "/api/v1/projects/p/secrets",
                e + "/secrets",
                e + "/secrets/export",
                e + "/providers/v/secrets",
                e + "/providers/v/secrets/export",
                e + "/providers/v/secrets/DB_URL",
                e + "/providers/v/secrets/DB_URL/versions/3",
                "/gateway/api/v1/projects/p/environments/dev/secrets",
        }) {
            assertTrue(E2EEncryptionInterceptor.requiresEnvelope("GET", p), p);
        }
        for (String p : new String[] {
                e + "/secrets/version",
                e + "/secrets/manifest",
                e + "/secrets/certificates",
                e + "/providers/v/secrets/hash",
                e + "/providers/v/secrets/DB_URL/metadata",
                e + "/providers/v/secrets/DB_URL/versions",
                e + "/providers/v/secrets/DB_URL/versions/latest",
                e + "/providers/v/secrets/DB_URL/rotation-policy",
                e + "/providers/v/secrets/import/preview",
                "/api/v1/tenants/me/zke",
                "/api/v1/projects/p",
        }) {
            assertFalse(E2EEncryptionInterceptor.requiresEnvelope("GET", p), p);
        }
        assertFalse(E2EEncryptionInterceptor.requiresEnvelope("POST", e + "/secrets"));
        assertFalse(E2EEncryptionInterceptor.requiresEnvelope("PUT", e + "/providers/v/secrets/DB_URL"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void assertRefused(BaxterClient.BaxterException ex, String code, String message) {
        var refused = E2EEResponseException.find(ex).orElseThrow(() -> new AssertionError("not refused: " + ex));
        assertEquals(code, refused.getCode());
        assertEquals(message, refused.getMessage());
        assertEquals(message, ex.getMessage());
        assertFalse(ex.toString().contains(SENTINEL_VALUE));
    }

    private static String otherPublicKey() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return Base64.getEncoder().encodeToString(kpg.generateKeyPair().getPublic().getEncoded());
    }

    /** The server side of the contract (EciesAlgorithm.Encrypt / contract-tests/stub/server.mjs encryptFor). */
    private static String encryptFor(String clientSpkiB64, String plaintext, boolean tamper) throws Exception {
        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey clientPub = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(clientSpkiB64)));
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair eph = kpg.generateKeyPair();

        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(eph.getPrivate());
        ka.doPhase(clientPub, true);
        byte[] shared = ka.generateSecret();

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(shared);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update("bella-e2ee-v1".getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 0x01);
        byte[] aesKey = mac.doFinal();

        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, nonce));
        byte[] out = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] ct = Arrays.copyOfRange(out, 0, out.length - 16);
        byte[] tag = Arrays.copyOfRange(out, out.length - 16, out.length);
        if (tamper) ct[0] ^= 0x01;

        var b64 = Base64.getEncoder();
        return "{\"encrypted\":true,\"algorithm\":\"ECDH-P256-HKDF-SHA256-AES256GCM\","
                + "\"serverPublicKey\":\"" + b64.encodeToString(eph.getPublic().getEncoded()) + "\","
                + "\"nonce\":\"" + b64.encodeToString(nonce) + "\","
                + "\"tag\":\"" + b64.encodeToString(tag) + "\","
                + "\"ciphertext\":\"" + b64.encodeToString(ct) + "\"}";
    }
}
