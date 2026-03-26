package io.bellabaxter;

import com.microsoft.kiota.authentication.AuthenticationProvider;
import com.microsoft.kiota.RequestInformation;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Kiota {@link AuthenticationProvider} that signs every request with
 * HMAC-SHA256 using the Bella Baxter API key format {@code bax-{keyId}-{signingSecret}}.
 *
 * <p>Signing algorithm:
 * <pre>
 * stringToSign = METHOD + "\n" + path + "\n" + sortedQueryString + "\n" + timestamp + "\n" + sha256HexOfBody
 * signature    = HMAC-SHA256(signingSecret, stringToSign)
 * </pre>
 * Headers added: {@code X-Bella-Key-Id}, {@code X-Bella-Timestamp}, {@code X-Bella-Signature}.
 */
public final class HmacAuthenticationProvider implements AuthenticationProvider {

    private final String keyId;
    private final byte[] signingSecret;
    private final String bellaClient;
    private final String appClient;

    /**
     * @param apiKey full API key in format {@code bax-{keyId_32hex}-{signingSecret_48hex}}
     */
    public HmacAuthenticationProvider(String apiKey) {
        this(apiKey, "bella-java-sdk", null);
    }

    /**
     * @param apiKey      full API key in format {@code bax-{keyId_32hex}-{signingSecret_48hex}}
     * @param bellaClient identifies the SDK (e.g. "bella-java-sdk")
     * @param appClient   optional user application name for audit logging (falls back to BELLA_BAXTER_APP_CLIENT env)
     */
    public HmacAuthenticationProvider(String apiKey, String bellaClient, String appClient) {
        String[] parts = apiKey.split("-", 3);
        if (parts.length != 3 || !"bax".equals(parts[0])) {
            throw new IllegalArgumentException("apiKey must be in format bax-{keyId}-{signingSecret}");
        }
        this.keyId = parts[1];
        this.signingSecret = HexFormat.of().parseHex(parts[2]);
        this.bellaClient = bellaClient;
        String envClient = System.getenv("BELLA_BAXTER_APP_CLIENT");
        this.appClient = appClient != null ? appClient : (envClient != null ? envClient : null);
    }

    @Override
    public void authenticateRequest(RequestInformation request, Map<String, Object> additionalAuthenticationContext) {
        try {
            URI uri = request.getUri();
                String method = request.httpMethod.toString();
                String path   = uri.getRawPath();

                // Sorted, URL-decoded query string (empty string if none)
                String rawQuery = uri.getRawQuery();
                String query = sortedQuery(rawQuery);

                // SHA-256 of body (empty body = SHA-256 of empty bytes)
                byte[] body = request.content != null ? request.content.readAllBytes() : new byte[0];
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                String bodyHash = HexFormat.of().formatHex(sha.digest(body));

                // Timestamp — UTC, no milliseconds
                String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
                        .replaceAll("\\.\\d+Z$", "Z");

                String stringToSign = method + "\n" + path + "\n" + query + "\n" + timestamp + "\n" + bodyHash;

                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
                String signature = HexFormat.of().formatHex(
                        mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));

                request.headers.add("X-Bella-Key-Id",    keyId);
                request.headers.add("X-Bella-Timestamp", timestamp);
                request.headers.add("X-Bella-Signature", signature);
                request.headers.add("X-Bella-Client",    bellaClient);
                if (appClient != null) {
                    request.headers.add("X-App-Client", appClient);
                }
            } catch (Exception ex) {
                throw new RuntimeException("bellabaxter: HMAC signing failed: " + ex.getMessage(), ex);
            }
    }

    /** Build the canonical query string: URL-decoded pairs sorted by key then value, re-encoded. */
    private static String sortedQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";

        TreeMap<String, String> sorted = new TreeMap<>();
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            sorted.put(k, v);
        }

        return sorted.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
