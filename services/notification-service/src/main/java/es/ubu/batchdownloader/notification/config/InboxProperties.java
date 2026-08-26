package es.ubu.batchdownloader.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Representa los datos inmutables de {@code InboxProperties}.
 *
 * @param leaseDuration Valor de {@code leaseDuration} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@ConfigurationProperties(prefix = "notification.inbox")
public record InboxProperties(Duration leaseDuration) {

    /**
     * Inicializa una instancia de {@code InboxProperties}.
     *
     * @param leaseDuration Valor de {@code leaseDuration} utilizado por la operación.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    public InboxProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("notification.inbox.lease-duration debe ser positivo");
        }
    }
}
