package es.ubu.batchdownloader.notification.infrastructure.mail;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Descifra el token justo antes de construir el enlace; admite eventos legacy en texto claro. */
@Component
public class NotificationTokenCipher {
    private static final String PREFIX = "enc:v1:";
    private static final byte[] AAD =
            "batch-downloader.notification-token.v1".getBytes(StandardCharsets.US_ASCII);
    private final SecretKeySpec key;

    public NotificationTokenCipher(
            @Value("${notification.token-encryption-key}") String encodedKey) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("notification_token_encryption_key_invalid", exception);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("notification_token_encryption_key_must_be_32_bytes");
        }
        key = new SecretKeySpec(decoded, "AES");
    }

    public String decryptOrLegacy(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("notification_token_required");
        if (!value.startsWith(PREFIX)) return value;
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(value.substring(PREFIX.length()));
            if (envelope.length < 29) throw new GeneralSecurityException("invalid_envelope");
            byte[] nonce = Arrays.copyOfRange(envelope, 0, 12);
            byte[] ciphertext = Arrays.copyOfRange(envelope, 12, envelope.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("notification_token_decryption_failed", exception);
        }
    }
}
