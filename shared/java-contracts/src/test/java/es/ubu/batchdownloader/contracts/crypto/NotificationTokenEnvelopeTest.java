package es.ubu.batchdownloader.contracts.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Comprueba compatibilidad binaria del sobre enc:v2 y rechazo de tokens o claves inválidos.
 *
 * @see es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope
 * @since 0.2.0-SNAPSHOT
 * @version 0.2.0-SNAPSHOT
 * @category Contratos compartidos
 */
class NotificationTokenEnvelopeTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String VECTOR =
            "enc:v2:AAECAwQFBgcICQoL_KRYN27Q65zvGz2LiXG_eDsiZdxkqfjInqYjJYcIrkgd";

    /**
     * Comprueba que una clave y nonce conocidos producen el vector canónico y permiten recuperar el
     * token original.
     */
    @Test
    void matchesTheVersionTwoContractVector() {
        NotificationTokenEnvelope envelope = new NotificationTokenEnvelope(KEY);

        assertThat(envelope.encrypt("token-de-contrato", "person@example.com", "MAGIC_LINK", new byte[] {
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11
                }))
                .isEqualTo(VECTOR);
        assertThat(envelope.decrypt(VECTOR, "person@example.com", "MAGIC_LINK")).isEqualTo("token-de-contrato");
    }

    /**
     * Comprueba el rechazo de texto claro, sobres manipulados y claves con longitud incorrecta.
     */
    @Test
    void rejectsPlaintextTamperingAndInvalidKeys() {
        NotificationTokenEnvelope envelope = new NotificationTokenEnvelope(KEY);
        assertThatThrownBy(() -> envelope.decrypt("legacy-plaintext-token", "person@example.com", "MAGIC_LINK"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("notification_token_envelope_version_required");
        assertThatThrownBy(() -> envelope.decrypt(VECTOR.substring(0, VECTOR.length() - 1) + "A", "person@example.com", "MAGIC_LINK"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("notification_token_decryption_failed");
        assertThatThrownBy(() -> new NotificationTokenEnvelope(
                        Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("notification_token_encryption_key_must_be_32_bytes");
    }

    @Test
    void rejectsRecipientOrPurposeChangesAndUnboundLegacyEnvelopes() {
        var envelope = new NotificationTokenEnvelope(KEY);
        assertThatThrownBy(() -> envelope.decrypt(VECTOR, "attacker@example.com", "MAGIC_LINK"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> envelope.decrypt(VECTOR, "person@example.com", "OTHER"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> envelope.decrypt("enc:v1:unbound", "person@example.com", "MAGIC_LINK"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(envelope.decrypt(VECTOR, " person@example.com ", "MAGIC_LINK"))
                .isEqualTo("token-de-contrato");
    }

    @Test
    void randomizedEncryptionRequiresACompleteContextAndValidEnvelope() {
        var envelope = new NotificationTokenEnvelope(KEY);
        String first = envelope.encrypt("token", "person@example.com", "MAGIC_LINK");
        String second = envelope.encrypt("token", "person@example.com", "MAGIC_LINK");
        assertThat(first).isNotEqualTo(second);
        assertThat(envelope.decrypt(first, "person@example.com", "MAGIC_LINK")).isEqualTo("token");
        for (String missing : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> envelope.encrypt("token", missing, "MAGIC_LINK"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> envelope.encrypt("token", "person@example.com", missing))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> envelope.encrypt(missing, "person@example.com", "MAGIC_LINK"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> envelope.encrypt("token", "person@example.com", "MAGIC_LINK", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> envelope.encrypt("token", "person@example.com", "MAGIC_LINK", new byte[11]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> envelope.decrypt(null, "person@example.com", "MAGIC_LINK"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> envelope.decrypt("enc:v2:AA", "person@example.com", "MAGIC_LINK"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new NotificationTokenEnvelope("invalid base64"))
                .isInstanceOf(IllegalStateException.class);
    }
}
