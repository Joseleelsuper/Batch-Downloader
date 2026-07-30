package es.ubu.batchdownloader.notification.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Representa los datos inmutables de {@code NotificationRequestedMessage}.
 *
 * @param eventId Valor de {@code eventId} incluido en el record.
 * @param type Valor de {@code type} incluido en el record.
 * @param schemaVersion Valor de {@code schemaVersion} incluido en el record.
 * @param occurredAt Valor de {@code occurredAt} incluido en el record.
 * @param correlationId Valor de {@code correlationId} incluido en el record.
 * @param causationId Valor de {@code causationId} incluido en el record.
 * @param payload Valor de {@code payload} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public record NotificationRequestedMessage(
        UUID eventId,
        String type,
        Integer schemaVersion,
        Instant occurredAt,
        String correlationId,
        String causationId,
        Payload payload) {

    /**
     * Representa los datos inmutables de {@code Payload}.
     *
     * @param recipient Valor de {@code recipient} incluido en el record.
     * @param template Valor de {@code template} incluido en el record.
     * @param parameters Valor de {@code parameters} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record Payload(
            String recipient,
            String template,
            Map<String, Object> parameters) {}
}
