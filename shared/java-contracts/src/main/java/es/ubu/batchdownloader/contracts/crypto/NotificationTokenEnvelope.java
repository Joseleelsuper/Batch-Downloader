package es.ubu.batchdownloader.contracts.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Contrato único del sobre AES-256-GCM utilizado por tokens de notificación. */
public final class NotificationTokenEnvelope {
    public static final String VERSION_PREFIX = "enc:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] AAD =
            "batch-downloader.notification-token.v1".getBytes(StandardCharsets.US_ASCII);

    private final SecretKeySpec key;
    private final SecureRandom random;

    /** Construye el contrato a partir de una clave AES de 32 bytes codificada en Base64. */
    public NotificationTokenEnvelope(String encodedKey) {
        this(encodedKey, new SecureRandom());
    }

    NotificationTokenEnvelope(String encodedKey, SecureRandom random) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("notification_token_encryption_key_invalid", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("notification_token_encryption_key_must_be_32_bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
        this.random = random;
    }

    /** Cifra un token y devuelve exclusivamente un sobre {@code enc:v1}. */
    public String encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return encrypt(plaintext, nonce);
    }

    /** Descifra un sobre v1 y rechaza texto claro o versiones desconocidas. */
    public String decrypt(String envelopeValue) {
        if (!isVersion1(envelopeValue)) {
            throw new IllegalArgumentException("notification_token_envelope_version_required");
        }
        try {
            byte[] envelope = Base64.getUrlDecoder()
                    .decode(envelopeValue.substring(VERSION_PREFIX.length()));
            if (envelope.length < NONCE_BYTES + TAG_BITS / Byte.SIZE + 1) {
                throw new GeneralSecurityException("invalid_envelope");
            }
            byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("notification_token_decryption_failed", exception);
        }
    }

    /** Indica si el valor declara la única versión aceptada por el contrato. */
    public static boolean isVersion1(String value) {
        return value != null && value.startsWith(VERSION_PREFIX);
    }

    String encrypt(String plaintext, byte[] nonce) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("notification_token_required");
        }
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("notification_token_nonce_must_be_12_bytes");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, envelope, 0, nonce.length);
            System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
            return VERSION_PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("notification_token_encryption_failed", exception);
        }
    }
}
