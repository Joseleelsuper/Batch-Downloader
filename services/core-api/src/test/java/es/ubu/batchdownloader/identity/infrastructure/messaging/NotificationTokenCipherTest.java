package es.ubu.batchdownloader.identity.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class NotificationTokenCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithVersionedRandomizedAesGcmEnvelopes() {
        NotificationTokenCipher cipher = new NotificationTokenCipher(KEY);

        String first = cipher.encrypt("raw-secret-token");
        String second = cipher.encrypt("raw-secret-token");

        assertThat(first).startsWith("enc:v1:").doesNotContain("raw-secret-token");
        assertThat(second).startsWith("enc:v1:").isNotEqualTo(first);
    }

    @Test
    void rejectsKeysThatAreNotExactly256Bits() {
        assertThatThrownBy(() -> new NotificationTokenCipher(
                Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("notification_token_encryption_key_must_be_32_bytes");
    }
}
