package io.bellabaxter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * OkHttp {@link Interceptor} that transparently handles E2EE for secrets responses.
 *
 * <ul>
 *   <li>Adds {@code X-E2E-Public-Key} header to requests targeting secrets endpoints.</li>
 *   <li>On response: if {@code encrypted=true}, decrypts the payload and replaces the
 *       response body with a normal {@link io.bellabaxter.generated.models.AllEnvironmentSecretsResponse}
 *       JSON so Kiota's deserialiser handles it normally.</li>
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

    private static final String SECRETS_PATH_SUFFIX = "/secrets";
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

        // Only inject E2EE header on secrets GET requests (not /secrets/version)
        String  path         = original.url().encodedPath();
        boolean isSecretsGet = path.endsWith(SECRETS_PATH_SUFFIX)
                && "GET".equalsIgnoreCase(original.method());

        Request request = original;
        if (isSecretsGet) {
            request = original.newBuilder()
                    .header(E2E_HEADER, e2ee.getPublicKeyBase64())
                    .build();
        }

        Response response = chain.proceed(request);

        if (!isSecretsGet || !response.isSuccessful()) {
            return response;
        }

        // #1050 (b) — the key was presented: on an envelope-required read, anything but an envelope that
        // decrypts is REFUSED (SDK_CONTRACT.md, "Rule: a presented key requires an envelope"). There is no
        // plaintext fallback. Every path this interceptor presents on is envelope-required today; the test
        // is still explicit so that widening the presentation cannot quietly widen what passes through.
        boolean envelopeRequired = requiresEnvelope(request.method(), path);

        ResponseBody body = response.body();
        byte[] bodyBytes = body == null ? new byte[0] : body.bytes();
        MediaType contentType = body == null ? null : body.contentType();

        JsonNode node;
        try {
            node = bodyBytes.length == 0 ? null : mapper.readTree(bodyBytes);
        } catch (IOException notJson) {
            node = null; // not JSON (a dotenv export) — not an envelope
        }

        boolean isEnvelope = node != null && node.isObject()
                && node.path("encrypted").isBoolean() && node.path("encrypted").booleanValue();

        if (!isEnvelope) {
            if (envelopeRequired) {
                response.close();
                throw E2EEResponseException.plaintext(path);
            }
            return response.newBuilder()
                    .body(ResponseBody.create(bodyBytes, contentType))
                    .build();
        }

        // Decrypt and reconstruct as a normal AllEnvironmentSecretsResponse JSON
        byte[] responseBytes;
        try {
            byte[]   plainBytes = e2ee.decryptRaw(bodyBytes);
            JsonNode decrypted  = mapper.readTree(plainBytes);

            if (decrypted.isObject() && decrypted.has("secrets")
                    && decrypted.get("secrets").isObject()) {
                // Full AllEnvironmentSecretsResponse — pass through directly.
                responseBytes = plainBytes;
            } else {
                // Legacy: array [{key,value}] or flat {K:V} → synthesise a response.
                Map<String, String> secrets = e2ee.decrypt(bodyBytes);
                var objectNode = mapper.createObjectNode();
                objectNode.put("environmentSlug", "");
                objectNode.put("environmentName", "");
                objectNode.put("version", 0);
                objectNode.put("lastModified", "");
                var secretsNode = mapper.createObjectNode();
                secrets.forEach(secretsNode::put);
                objectNode.set("secrets", secretsNode);
                responseBytes = mapper.writeValueAsBytes(objectNode);
            }
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
