package es.ubu.batchdownloader.identity.infrastructure.messaging;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Adapta el contrato criptográfico compartido a la configuración de Core API. */
@Configuration
class IdentityMessagingConfiguration {
    @Bean
    NotificationTokenEnvelope notificationTokenEnvelope(
            @Value("${app.notification-token-encryption-key}") String encodedKey) {
        return new NotificationTokenEnvelope(encodedKey);
    }
}
