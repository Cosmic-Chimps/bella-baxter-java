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

        // Capture wrapped DEK if the server returned one (ZKE flow)
        if (onWrappedDekReceived != null) {
            String wrappedDek = response.header("X-Bella-Wrapped-Dek");
            if (wrappedDek != null) {
                String leaseExpires = response.header("X-Bella-Lease-Expires");
                onWrappedDekReceived.accept(wrappedDek, leaseExpires);
            }
        }

        ResponseBody body = response.body();
        if (body == null) return response;

        byte[]   bodyBytes = body.bytes();
        JsonNode node      = mapper.readTree(bodyBytes);

        if (!node.path("encrypted").asBoolean(false)) {
            // Not encrypted — return as-is
            MediaType contentType = body.contentType();
            return response.newBuilder()
                    .body(ResponseBody.create(bodyBytes, contentType))
                    .build();
        }

        // Decrypt and reconstruct as a normal AllEnvironmentSecretsResponse JSON
        try {
            byte[]   plainBytes = e2ee.decryptRaw(bodyBytes);
            JsonNode decrypted  = mapper.readTree(plainBytes);

            byte[] responseBytes;
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

            return response.newBuilder()
                    .body(ResponseBody.create(responseBytes, MediaType.get("application/json")))
                    .build();
        } catch (Exception ex) {
            throw new IOException("bellabaxter: E2EE decryption failed: " + ex.getMessage(), ex);
        }
    }
}
