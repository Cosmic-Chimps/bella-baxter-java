package io.bellabaxter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.Map;

/**
 * OkHttp {@link Interceptor} that transparently handles E2EE for secrets responses.
 *
 * <ul>
 *   <li>Adds {@code X-E2E-Public-Key} header to requests targeting secrets endpoints.</li>
 *   <li>On response: if {@code encrypted=true}, decrypts the payload and replaces the
 *       response body with a normal {@link io.bellabaxter.generated.models.AllEnvironmentSecretsResponse}
 *       JSON so Kiota's deserializer handles it normally.</li>
 * </ul>
 */
final class E2EEncryptionInterceptor implements Interceptor {

    private static final String SECRETS_PATH_SUFFIX = "/secrets";
    private static final String E2E_HEADER = "X-E2E-Public-Key";

    private final E2EEncryption e2ee = new E2EEncryption();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();

        // Only inject E2EE header on secrets GET requests (not /secrets/version)
        String path = original.url().encodedPath();
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

        ResponseBody body = response.body();
        if (body == null) return response;

        byte[] bodyBytes = body.bytes();
        JsonNode node = mapper.readTree(bodyBytes);

        if (!node.path("encrypted").asBoolean(false)) {
            // Not encrypted — return as-is
            MediaType contentType = body.contentType();
            return response.newBuilder()
                    .body(ResponseBody.create(bodyBytes, contentType))
                    .build();
        }

        // Decrypt and reconstruct as a normal AllEnvironmentSecretsResponse JSON
        try {
            byte[] plainBytes = e2ee.decryptRaw(bodyBytes);
            JsonNode decrypted = mapper.readTree(plainBytes);

            byte[] responseBytes;
            if (decrypted.isObject() && decrypted.has("secrets")
                    && decrypted.get("secrets").isObject()) {
                // Full AllEnvironmentSecretsResponse — pass through directly.
                // The decrypted JSON already has environmentSlug, version, lastModified, etc.
                responseBytes = plainBytes;
            } else {
                // Legacy: array [{key,value}] or flat {K:V} → synthesize a response.
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
