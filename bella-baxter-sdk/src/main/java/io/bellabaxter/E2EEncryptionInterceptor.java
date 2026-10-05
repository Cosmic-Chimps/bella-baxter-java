package io.bellabaxter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * OkHttp {@link Interceptor} that transparently handles E2EE for secrets responses.
 *
 * <ul>
 *   <li><b>#1162:</b> adds {@code X-E2E-Public-Key} to EVERY envelope-required read — the seven {@code GET}s
 *       that carry secret values ({@link #requiresEnvelope}, apps/sdk/SDK_CONTRACT.md, "Rule: the key is
 *       presented on every envelope-required read"), whichever call issued it — and to nothing else.</li>
 *   <li>On response: decrypts the envelope and hands the plaintext on UNCHANGED — the same JSON the server
 *       sends without a key (an {@code AllEnvironmentSecretsResponse}, a {@code {key: value}} export, an
 *       array of secret items, one item, …) — so Kiota's deserialiser or a raw caller sees the real shape.</li>
 *   <li>When {@code onWrappedDekReceived} is set (ZKE mode), captures any
 *       {@code X-Bella-Wrapped-Dek} / {@code X-Bella-Lease-Expires} response headers.</li>
 *   <li><b>#1050 (b):</b> once the key was presented on an envelope-required read
 *       ({@link #requiresEnvelope}), a {@code 2xx} answer that is not an envelope throws
 *       {@link E2EEResponseException} ({@code e2ee-plaintext-response}), and an envelope that does not
 *       decrypt (tampered, malformed, or to another key) throws it with {@code e2ee-decryption-failed}.
 *       Never a plaintext fallback.</li>
 * </ul>
 */
final class E2EEncryptionInterceptor implements Interceptor {

    private static final String E2E_HEADER          = "X-E2E-Public-Key";

    private final E2EEncryption              e2ee;
    private final BiConsumer<String, String> onWrappedDekReceived; // may be null
    private final ObjectMapper               mapper = new ObjectMapper();

    /** Ephemeral-key constructor — backward-compatible default. */
    E2EEncryptionInterceptor() {
        this.e2ee                 = new E2EEncryption();
        this.onWrappedDekReceived = null;
    }

    /**
     * ZKE constructor — uses a persistent device key and optionally captures the
     * wrapped DEK returned by the server.
     *
     * @param e2ee                pre-built {@link E2EEncryption} from a PKCS#8 private key
     * @param onWrappedDekReceived callback invoked with {@code (wrappedDek, leaseExpires)}
     *                            whenever the server returns an {@code X-Bella-Wrapped-Dek}
     *                            header; may be {@code null}
     */
    E2EEncryptionInterceptor(E2EEncryption e2ee, BiConsumer<String, String> onWrappedDekReceived) {
        this.e2ee                 = e2ee;
        this.onWrappedDekReceived = onWrappedDekReceived;
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();

        // #1162 — the key is presented on exactly the envelope-required reads, so every read that carries
        // secret values is end-to-end encrypted, and the #1050 rule binds on each of them.
        String  path      = original.url().encodedPath();
        boolean presented = requiresEnvelope(original.method(), path);

        Request request = original;
        if (presented) {
            request = original.newBuilder()
                    .header(E2E_HEADER, e2ee.getPublicKeyBase64())
                    .build();
        }

        Response response = chain.proceed(request);

        if (!presented || !response.isSuccessful()) {
            return response;
        }

        // #1050 (b) — the key was presented on an envelope-required read: anything but an envelope that
        // decrypts is REFUSED (SDK_CONTRACT.md, "Rule: a presented key requires an envelope"). There is no
        // plaintext fallback.
        ResponseBody body = response.body();
        byte[] bodyBytes = body == null ? new byte[0] : body.bytes();

        JsonNode node;
        try {
            node = bodyBytes.length == 0 ? null : mapper.readTree(bodyBytes);
        } catch (IOException notJson) {
            node = null; // not JSON (a dotenv export) — not an envelope
        }

        boolean isEnvelope = node != null && node.isObject()
                && node.path("encrypted").isBoolean() && node.path("encrypted").booleanValue();

        if (!isEnvelope) {
            response.close();
            throw E2EEResponseException.plaintext(path);
        }

        // The plaintext is the server's own JSON for this read; it is handed on as is (#1162). Reshaping it
        // (the old "legacy" {secrets: …} synthesis) would turn a getSecret item or a listSecrets array into
        // a different document that merely decrypted correctly.
        byte[] responseBytes;
        try {
            responseBytes = e2ee.decryptRaw(bodyBytes);
            mapper.readTree(responseBytes); // must be JSON: an envelope that opens to garbage is not a value
        } catch (Exception ex) {
            response.close();
            throw E2EEResponseException.decryptionFailed(path, ex);
        }

        // Capture wrapped DEK if the server returned one (ZKE flow) — only for an answer that decrypted.
        if (onWrappedDekReceived != null) {
            String wrappedDek = response.header("X-Bella-Wrapped-Dek");
            if (wrappedDek != null) {
                String leaseExpires = response.header("X-Bella-Lease-Expires");
                onWrappedDekReceived.accept(wrappedDek, leaseExpires);
            }
        }

        return response.newBuilder()
                .body(ResponseBody.create(responseBytes, MediaType.get("application/json")))
                .build();
    }

    /**
     * Whether the server encrypts this read's {@code 2xx} body whenever {@code X-E2E-Public-Key} is presented
     * — the envelope-required reads of apps/sdk/SDK_CONTRACT.md. Every other path (writes,
     * {@code …/secrets/version}, {@code …/hash}, {@code …/{key}/metadata}, …) is answered in plain JSON.
     */
    static boolean requiresEnvelope(String method, String path) {
        if (method == null || path == null || !"GET".equalsIgnoreCase(method)) return false;
        final String marker = "/api/v1/projects/";
        int i = path.indexOf(marker);
        if (i < 0) return false;
        String[] s = path.substring(i + marker.length()).split("/", -1);
        // s[0] = project
        int n = s.length;
        if (n == 2) return "secrets".equals(s[1]);                                   // listGlobalSecrets
        if (n < 4 || !"environments".equals(s[1])) return false;
        if (n == 4) return "secrets".equals(s[3]);                                   // getAllEnvironmentSecrets
        if (n == 5) return "secrets".equals(s[3]) && "export".equals(s[4]);          // exportEnvironmentSecrets
        if (!"providers".equals(s[3]) || n < 6 || !"secrets".equals(s[5])) return false;
        if (n == 6) return true;                                                     // listSecrets
        if (n == 7) return !s[6].isEmpty() && !"hash".equals(s[6]);                  // exportSecrets / getSecret
        if (n == 9) return !s[6].isEmpty() && "versions".equals(s[7])                // getSecretVersion
                && !s[8].isEmpty() && s[8].chars().allMatch(c -> c >= '0' && c <= '9');
        return false;
    }
}
