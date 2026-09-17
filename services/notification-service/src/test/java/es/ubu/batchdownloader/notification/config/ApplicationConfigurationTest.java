package es.ubu.batchdownloader.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que el reloj y el sobre cifrado creados por Spring son compatibles con el contrato
 * compartido.
 *
 * @see es.ubu.batchdownloader.notification.config.ApplicationConfiguration
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class ApplicationConfigurationTest {
    private final ApplicationConfiguration configuration = new ApplicationConfiguration();

    /**
     * Comprueba la zona UTC del reloj y el intercambio de tokens con un sobre construido con la
     * misma clave.
     */
    @Test
    void providesUtcClockAndCompatibleEncryptedEnvelope() {
        String key = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

        assertThat(configuration.systemClock().getZone()).isEqualTo(ZoneOffset.UTC);
        NotificationTokenEnvelope envelope = configuration.notificationTokenEnvelope(key);
        String protectedToken = envelope.encrypt("single-use-token");

        assertThat(protectedToken).startsWith("enc:v1:");
        assertThat(envelope.decrypt(protectedToken)).isEqualTo("single-use-token");
    }
}
