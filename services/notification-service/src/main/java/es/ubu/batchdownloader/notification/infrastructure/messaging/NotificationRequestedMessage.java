package es.ubu.batchdownloader.notification.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Representa el sobre JSON de una solicitud de correo antes de aplicar las invariantes del dominio.
 * Los campos opcionales del transporte permiten deserializar y diagnosticar mensajes incompletos
 * en el conversor, que comprueba tipo, versión, trazabilidad y contenido.
 *
 * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
 * @param type Tipo de evento indicado por el productor; debe ser notification.email.requested.
 * @param schemaVersion Versión del sobre, actualmente 1; null se rechaza al validar la entrada.
 * @param occurredAt Instante UTC en que el productor emitió el evento.
 * @param correlationId Identificador de trazabilidad del flujo que solicitó el correo.
 * @param causationId Identificador del evento causante; puede ser null.
 * @param payload Contenido de la solicitud de correo, pendiente de validación.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.NotificationRequestedMessageMapper
 *
 * @see es.ubu.batchdownloader.notification.domain.EmailNotification
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
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
     * Contiene destinatario, nombre de plantilla y parámetros tal como llegan por RabbitMQ, aún sin
     * validar.
     *
     * @param recipient Dirección de correo del destinatario, sin nombre visible ni lista de
     *     direcciones.
     *
     * @param template Finalidad del correo, que determina sus parámetros y proveedor.
     * @param parameters Valores escalares de la plantilla; los tokens de identidad llegan cifrados.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @see
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.NotificationRequestedMessageMapper
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Notificaciones
     */
    public record Payload(
            String recipient,
            String template,
            Map<String, Object> parameters) {}
}
