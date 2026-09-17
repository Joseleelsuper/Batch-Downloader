package es.ubu.batchdownloader.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Establece cuánto tiempo un consumidor mantiene reservada una solicitud de correo.
 *
 * @param leaseDuration Duración positiva de la reserva antes de permitir su recuperación.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.persistence.JdbcNotificationInbox
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@ConfigurationProperties(prefix = "notification.inbox")
public record InboxProperties(Duration leaseDuration) {

    /**
     * Impide iniciar el inbox con reservas de duración nula o no positiva.
     *
     * @param leaseDuration Duración positiva de la reserva antes de permitir su recuperación.
     * @throws IllegalArgumentException si falta la duración o no es estrictamente positiva.
     */
    public InboxProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("notification.inbox.lease-duration debe ser positivo");
        }
    }
}
