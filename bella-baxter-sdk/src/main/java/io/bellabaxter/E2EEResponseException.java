package io.bellabaxter;

import java.io.IOException;
import java.util.Optional;

/**
 * A secrets response was refused because it was not a decryptable E2EE envelope (#1050).
 *
 * <p>Once this SDK has presented its {@code X-E2E-Public-Key} on an envelope-required read, a {@code 2xx}
 * answer that is plaintext, a tampered envelope, or an envelope encrypted to another key is an error, never a
 * value (apps/sdk/SDK_CONTRACT.md, "Rule: a presented key requires an envelope"). There is no plaintext
 * fallback.
 *
 * <p>Thrown from the OkHttp interceptor, so it is an {@link IOException}. The Kiota adapter wraps it in a
 * {@link RuntimeException} whose message still carries the code; {@link BaxterClient} unwraps it into a
 * {@link BaxterClient.BaxterException} whose cause is this exception. Use {@link #find(Throwable)} to locate
 * it in any cause chain.
 *
 * <p>The message names the request path and the code, never the body, ciphertext or key material.
 */
public final class E2EEResponseException extends IOException {

    /** The key was presented and the answer was not an envelope (plain secrets, or not JSON at all). */
    public static final String PLAINTEXT_RESPONSE = "e2ee-plaintext-response";

    /** The answer claimed to be an envelope but did not decrypt (malformed, tampered, or to another key). */
    public static final String DECRYPTION_FAILED = "e2ee-decryption-failed";

    private final String code;
    private final String path;

    private E2EEResponseException(String code, String path, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.path = path;
    }

    static E2EEResponseException plaintext(String path) {
        return new E2EEResponseException(PLAINTEXT_RESPONSE, path,
                "E2EE response expected but plaintext received for " + path + "; refusing it ("
                        + PLAINTEXT_RESPONSE + ")",
                null);
    }

    static E2EEResponseException decryptionFailed(String path, Throwable cause) {
        return new E2EEResponseException(DECRYPTION_FAILED, path,
                "E2EE response could not be decrypted for " + path + "; refusing it ("
                        + DECRYPTION_FAILED + ")",
                cause);
    }

    /** {@link #PLAINTEXT_RESPONSE} or {@link #DECRYPTION_FAILED}. */
    public String getCode() { return code; }

    /** The request path whose response was refused. */
    public String getPath() { return path; }

    /** The first {@code E2EEResponseException} in {@code t}'s cause chain, if any. */
    public static Optional<E2EEResponseException> find(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof E2EEResponseException e) return Optional.of(e);
            if (c.getCause() == c) break;
        }
        return Optional.empty();
    }
}
