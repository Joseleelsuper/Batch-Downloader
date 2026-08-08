package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class NotificationTokenCipherTest {
    private static final byte[] KEY_BYTES = new byte[32];
    private static final String KEY = Base64.getEncoder().encodeToString(KEY_BYTES);

    @Test
    void decryptsTheVersionedCoreEnvelopeAndKeepsLegacyCompatibility() throws Exception {
        NotificationTokenCipher cipher = new NotificationTokenCipher(KEY);

        assertThat(cipher.decryptOrLegacy(encrypt("delivery-token"))).isEqualTo("delivery-token");
        assertThat(cipher.decryptOrLegacy("legacy-plaintext-token"))
                .isEqualTo("legacy-plaintext-token");
    }

    @Test
    void rejectsTamperedCiphertextWithoutReturningAnyPartialSecret() throws Exception {
        NotificationTokenCipher cipher = new NotificationTokenCipher(KEY);
        String envelope = encrypt("delivery-token");
        String tampered = envelope.substring(0, envelope.length() - 1)
                + (envelope.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> cipher.decryptOrLegacy(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("notification_token_decryption_failed");
    }

    private static String encrypt(String value) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY_BYTES, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD("batch-downloader.notification-token.v1"
                .getBytes(StandardCharsets.US_ASCII));
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] envelope = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, envelope, 0, nonce.length);
        System.arraycopy(ciphertext, 0, envelope, nonce.length, ciphertext.length);
        return "enc:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
    }
}
