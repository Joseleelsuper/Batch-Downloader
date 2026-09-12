package es.ubu.batchdownloader.identity.infrastructure.messaging;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Proporciona el cifrado autenticado de los permisos de identidad que atraviesan el outbox y
 * RabbitMQ.
 *
 * @see es.ubu.batchdownloader.identity.infrastructure.messaging.IdentityOutboxPublisher
 * @see es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Configuration
class IdentityMessagingConfiguration {
    /**
     * Construye el cifrador de tokens a partir de la clave compartida con el consumidor de correo.
     *
     * @param encodedKey Clave AES de 32 bytes codificada en Base64 compartida con el consumidor de
     *     notificaciones.
     * @return sobre AES-GCM para proteger los tokens de los eventos.
     */
    @Bean
    NotificationTokenEnvelope notificationTokenEnvelope(
            @Value("${app.notification-token-encryption-key}") String encodedKey) {
        return new NotificationTokenEnvelope(encodedKey);
    }
}
