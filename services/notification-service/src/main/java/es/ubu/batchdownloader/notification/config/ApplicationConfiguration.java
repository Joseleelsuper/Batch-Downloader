package es.ubu.batchdownloader.notification.config;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Define la configuración utilizada por {@code ApplicationConfiguration}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Configuration
public class ApplicationConfiguration {

    /**
     * Ejecuta la operación {@code systemClock}.
     *
     * @return Resultado producido por {@code systemClock}.
     */
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    /** Comparte exactamente el mismo contrato criptográfico que Core API. */
    @Bean
    NotificationTokenEnvelope notificationTokenEnvelope(
            @Value("${notification.token-encryption-key}") String encodedKey) {
        return new NotificationTokenEnvelope(encodedKey);
    }
}
