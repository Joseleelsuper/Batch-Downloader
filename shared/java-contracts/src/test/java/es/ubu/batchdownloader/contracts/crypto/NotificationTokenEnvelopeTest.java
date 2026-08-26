package es.ubu.batchdownloader.contracts.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Vectores compartidos que impiden divergencias entre productor y consumidor. */
class NotificationTokenEnvelopeTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String VECTOR =
            "enc:v1:AAECAwQFBgcICQoL_KRYN27Q65zvGz2LiXG_eDt1QoKMNW5dUVCGvIhioCnl";

    @Test
    void matchesTheVersionOneContractVector() {
        NotificationTokenEnvelope envelope = new NotificationTokenEnvelope(KEY);

        assertThat(envelope.encrypt("token-de-contrato", new byte[] {
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
                }))
                .isEqualTo(VECTOR);
        assertThat(envelope.decrypt(VECTOR)).isEqualTo("token-de-contrato");
    }

    @Test
    void rejectsPlaintextTamperingAndInvalidKeys() {
        NotificationTokenEnvelope envelope = new NotificationTokenEnvelope(KEY);
        assertThatThrownBy(() -> envelope.decrypt("legacy-plaintext-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("notification_token_envelope_version_required");
        assertThatThrownBy(() -> envelope.decrypt(VECTOR.substring(0, VECTOR.length() - 1) + "A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("notification_token_decryption_failed");
        assertThatThrownBy(() -> new NotificationTokenEnvelope(
                        Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("notification_token_encryption_key_must_be_32_bytes");
    }
}
