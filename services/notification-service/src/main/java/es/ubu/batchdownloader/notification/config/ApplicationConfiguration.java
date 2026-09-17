package es.ubu.batchdownloader.notification.config;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proporciona el reloj UTC y el descifrado del sobre de tokens que comparten Core y Notification.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.application.ProcessEmailNotification
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Configuration
public class ApplicationConfiguration {

    /**
     * Proporciona un reloj UTC común para reservas y registros de actividad.
     *
     * @return reloj del sistema con zona UTC.
     */
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    /**
     * Configura el descifrado autenticado de tokens emitidos por Core para los correos de
     * identidad.
     *
     * @param encodedKey Clave Base64 de 32 bytes compartida con el productor de tokens cifrados.
     * @return conversor del sobre enc:v1 con la clave configurada.
     * @see es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope
     */
    @Bean
    NotificationTokenEnvelope notificationTokenEnvelope(
            @Value("${notification.token-encryption-key}") String encodedKey) {
        return new NotificationTokenEnvelope(encodedKey);
    }
}
