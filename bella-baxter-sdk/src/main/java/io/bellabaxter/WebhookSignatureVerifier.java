package io.bellabaxter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verifies the {@code X-Bella-Signature} header on incoming Bella Baxter webhook requests.
 *
 * <p>Signature format: {@code X-Bella-Signature: t={unix_epoch_seconds},v1={hmac_sha256_hex}}
 *
 * <p>Signing algorithm:
 * <pre>
 * signingInput = "{t}.{rawBodyJson}"          (UTF-8)
 * hmac         = HMAC-SHA256(UTF8(secret), UTF8(signingInput))
 * signature    = lowercaseHex(hmac)
 * </pre>
 *
 * <p>The HMAC key is the raw signing secret string (the full {@code whsec-xxx} value,
 * UTF-8 encoded) — it is <em>not</em> hex-decoded before use.
 */
public final class WebhookSignatureVerifier {

    private static final int DEFAULT_TOLERANCE_SECONDS = 300;

    /**
     * Verifies the {@code X-Bella-Signature} header with the default tolerance of 300 seconds.
     *
     * @param secret          the {@code whsec-xxx} signing secret (raw string, not hex-decoded)
     * @param signatureHeader value of the {@code X-Bella-Signature} header
     * @param rawBody         raw request body string
     * @return {@code true} if the signature is valid and the timestamp is within tolerance
     */
    public static boolean verify(String secret, String signatureHeader, String rawBody) {
        return verify(secret, signatureHeader, rawBody, DEFAULT_TOLERANCE_SECONDS);
    }

    /**
     * Verifies the {@code X-Bella-Signature} header.
     *
     * @param secret            the {@code whsec-xxx} signing secret (raw string, not hex-decoded)
     * @param signatureHeader   value of the {@code X-Bella-Signature} header
     * @param rawBody           raw request body string
     * @param toleranceSeconds  maximum age of the timestamp in seconds
     * @return {@code true} if the signature is valid and the timestamp is within tolerance
     */
    public static boolean verify(String secret, String signatureHeader, String rawBody, int toleranceSeconds) {
        long timestamp = Long.MIN_VALUE;
        String v1 = null;

        for (String part : signatureHeader.split(",")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("t=")) {
                try {
                    timestamp = Long.parseLong(trimmed.substring(2));
                } catch (NumberFormatException e) {
                    return false;
                }
            } else if (trimmed.startsWith("v1=")) {
                v1 = trimmed.substring(3);
            }
        }

        if (timestamp == Long.MIN_VALUE || v1 == null) {
            return false;
        }

        long age = Math.abs(Instant.now().getEpochSecond() - timestamp);
        if (age > toleranceSeconds) {
            return false;
        }

        try {
            String signingInput = timestamp + "." + rawBody;
            Mac mac = Mac.getInstance("HmacSHA256");
            // Key is the raw UTF-8 encoded secret string — not hex-decoded
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmacBytes = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(hmacBytes);

            // MessageDigest.isEqual provides constant-time comparison
            return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                v1.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }

    private WebhookSignatureVerifier() {}
}
