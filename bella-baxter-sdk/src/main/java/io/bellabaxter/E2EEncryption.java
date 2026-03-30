package io.bellabaxter;

import java.security.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * ECDH-P256-HKDF-SHA256-AES256GCM client-side decryption.
 *
 * <p>The Bella Baxter API encrypts secrets responses when the client sends an
 * {@code X-E2E-Public-Key} header.  This class generates the P-256 key pair
 * and decrypts the encrypted payload using Java's standard crypto libraries.
 *
 * <pre>{@code
 * E2EEncryption e2e = new E2EEncryption();
 * // Header to add: e2e.getPublicKeyBase64()
 * Map<String,String> secrets = e2e.decrypt(responseBodyBytes);
 * }</pre>
 */
public final class E2EEncryption {

    private static final String HKDF_INFO = "bella-e2ee-v1";

    private final PrivateKey privateKey;
    private final String     publicKeyBase64; // base64-encoded SubjectPublicKeyInfo (SPKI)

    /** Generate a new P-256 key pair. */
    public E2EEncryption() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
            KeyPair kp = kpg.generateKeyPair();
            this.privateKey     = kp.getPrivate();
            this.publicKeyBase64 = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("E2EEncryption: key generation failed", e);
        }
    }

    /**
     * Base64-encoded SubjectPublicKeyInfo (SPKI) of the client's ephemeral public key.
     * Send this as the value of the {@code X-E2E-Public-Key} request header.
     */
    public String getPublicKeyBase64() {
        return publicKeyBase64;
    }

    /**
     * Decrypt an encrypted secrets payload from the Bella Baxter API.
     *
     * @param responseBodyBytes raw JSON bytes of the API response
     * @return decrypted {@code Map<String, String>} of secrets
     * @throws Exception if decryption fails
     */
    public Map<String, String> decrypt(byte[] responseBodyBytes) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBodyBytes);

        if (!root.path("encrypted").asBoolean(false)) {
            // Plain response — return the secrets map as-is
            Map<String, String> plain = new HashMap<>();
            root.fields().forEachRemaining(e -> {
                if (e.getValue().isTextual()) plain.put(e.getKey(), e.getValue().asText());
            });
            return plain;
        }

        byte[] plaintext = decryptCiphertext(root);

        // Parse JSON → Map<String,String>.
        // Three possible formats returned by the server:
        //   1. Full AllEnvironmentSecretsResponse: {"environmentSlug":..., "secrets":{...}, ...}
        //   2. Array of SecretItem:                [{key:"K", value:"V"}, ...]
        //   3. Legacy flat dict:                   {"K": "V", ...}
        com.fasterxml.jackson.databind.JsonNode secretsNode = mapper.readTree(plaintext);
        Map<String, String> secrets = new HashMap<>();

        if (secretsNode.isObject() && secretsNode.has("secrets")
                && secretsNode.get("secrets").isObject()) {
            // Full AllEnvironmentSecretsResponse — extract nested secrets dict.
            secretsNode.get("secrets").fields()
                    .forEachRemaining(e -> secrets.put(e.getKey(), e.getValue().asText("")));
        } else if (secretsNode.isArray()) {
            // Array of {key, value, ...} items.
            secretsNode.forEach(item -> {
                if (item.has("key")) secrets.put(item.get("key").asText(), item.path("value").asText(""));
            });
        } else {
            // Legacy flat object {KEY: VALUE, ...}.
            secretsNode.fields()
                    .forEachRemaining(e -> secrets.put(e.getKey(), e.getValue().asText()));
        }
        return secrets;
    }

    /**
     * Decrypt the raw plaintext bytes of an encrypted payload.
     *
     * <p>Unlike {@link #decrypt(byte[])} this method returns the full decrypted JSON bytes
     * without any parsing or transformation, so the caller can handle the response shape
     * itself (e.g. to preserve {@code version}, {@code environmentSlug}, etc.)
     *
     * @param responseBodyBytes raw JSON bytes of the {@code E2EEncryptedPayload} response
     * @return raw decrypted plaintext bytes (the original JSON the server encrypted)
     * @throws Exception if decryption fails or the payload is not encrypted
     */
    public byte[] decryptRaw(byte[] responseBodyBytes) throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(responseBodyBytes);
        return decryptCiphertext(root);
    }

    /** Core ECDH + HKDF + AES-GCM decryption — returns raw plaintext bytes. */
    private byte[] decryptCiphertext(com.fasterxml.jackson.databind.JsonNode root) throws Exception {
        byte[] serverPubBytes = Base64.getDecoder().decode(root.get("serverPublicKey").asText());
        byte[] nonce          = Base64.getDecoder().decode(root.get("nonce").asText());
        byte[] tag            = Base64.getDecoder().decode(root.get("tag").asText());
        byte[] ciphertext     = Base64.getDecoder().decode(root.get("ciphertext").asText());

        // 1. Import server ephemeral public key (SPKI / X.509 DER)
        KeyFactory kf = KeyFactory.getInstance("EC");
        PublicKey serverPub = kf.generatePublic(new X509EncodedKeySpec(serverPubBytes));

        // 2. ECDH → raw shared secret
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(privateKey);
        ka.doPhase(serverPub, true);
        byte[] sharedSecret = ka.generateSecret();

        // 3. HKDF-SHA256 → 32-byte AES key  (salt = 32 zeros per RFC 5869 §2.2)
        byte[] aesKey = hkdfSHA256(sharedSecret, HKDF_INFO.getBytes("UTF-8"));

        // 4. AES-256-GCM decrypt
        SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, nonce));
        // Java AES/GCM/NoPadding expects ciphertext + tag concatenated.
        byte[] combined = new byte[ciphertext.length + tag.length];
        System.arraycopy(ciphertext, 0, combined, 0,                ciphertext.length);
        System.arraycopy(tag,        0, combined, ciphertext.length, tag.length);

        return cipher.doFinal(combined);
    }

    // ── HKDF-SHA256 (RFC 5869) ─────────────────────────────────────────────────

    /**
     * Derives a 32-byte key using HKDF-SHA256 with a zero salt.
     *
     * <p>Matches .NET {@code HKDF.DeriveKey(HashAlgorithmName.SHA256, ikm,
     * outputLength: 32, salt: null, info: info)}.
     */
    private static byte[] hkdfSHA256(byte[] ikm, byte[] info) throws Exception {
        // Extract: PRK = HMAC-SHA256(salt = zeros[32], IKM)
        byte[] salt = new byte[32]; // SHA-256 hash length
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);

        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01)  — 32 bytes = 1 block
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(info);
        mac.update((byte) 0x01);
        return mac.doFinal(); // 32 bytes
    }
}
