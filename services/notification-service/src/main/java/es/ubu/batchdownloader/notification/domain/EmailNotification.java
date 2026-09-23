package es.ubu.batchdownloader.notification.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Transporta un correo validado desde el consumidor de eventos hasta el proveedor.
 *
 * Conserva la identidad de la entrega para el inbox y la idempotencia del proveedor; copia los
 * parámetros para impedir modificaciones posteriores. La validación específica de cada plantilla
 * se realiza al convertir el mensaje de RabbitMQ. Solo se admiten correos de acceso mediante
 * magic link.
 *
 * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
 * @param occurredAt Instante UTC en que el productor emitió el evento.
 * @param correlationId Identificador de trazabilidad del flujo que solicitó el correo.
 * @param causationId Identificador del evento causante; puede ser null.
 * @param recipient Dirección de correo del destinatario, sin nombre visible ni lista de
 *     direcciones.
 *
 * @param locale Código de idioma capturado al solicitar el enlace.
 * @param template Finalidad del correo, que determina sus parámetros y proveedor.
 * @param parameters Valores escalares de la plantilla; los tokens de identidad llegan cifrados.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.NotificationRequestedMessageMapper
 *
 * @see es.ubu.batchdownloader.notification.application.port.NotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
public record EmailNotification(
        UUID eventId,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String recipient,
        String locale,
        Template template,
        Map<String, Object> parameters) {

    /**
     * Tipo de evento aceptado por el consumidor y registrado en el inbox.
     */
    public static final String EVENT_TYPE = "notification.email.requested";
    /**
     * Versión del sobre de mensajería aceptada por el conversor de entrada.
     */
    public static final int SCHEMA_VERSION = 1;

    /**
     * Exige identidad, fecha, correlación, destinatario y plantilla; conserva una copia inmutable
     * de los parámetros.
     *
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @param occurredAt Instante UTC en que el productor emitió el evento.
     * @param correlationId Identificador de trazabilidad del flujo que solicitó el correo.
     * @param causationId Identificador del evento causante; puede ser null.
     * @param recipient Dirección de correo del destinatario, sin nombre visible ni lista de
     *     direcciones.
     *
     * @param template Finalidad del correo, que determina sus parámetros y proveedor.
     * @param parameters Valores escalares de la plantilla; los tokens de identidad llegan cifrados.
     * @throws NullPointerException si falta un valor obligatorio o el mapa contiene claves o
     *     valores null.
     *
     * @throws IllegalArgumentException si correlación o destinatario están vacíos.
     */
    public EmailNotification {
        eventId = Objects.requireNonNull(eventId, "eventId no puede ser null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt no puede ser null");
        correlationId = requireText(correlationId, "correlationId");
        recipient = requireText(recipient, "recipient");
        locale = locale == null || locale.isBlank()
                ? "es"
                : locale.strip().toLowerCase(java.util.Locale.ROOT);
        template = Objects.requireNonNull(template, "template no puede ser null");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters no puede ser null"));
    }

    /**
     * Lee como texto un parámetro obligatorio y elimina sus espacios exteriores.
     *
     * @param name Nombre del parámetro obligatorio de la plantilla.
     * @return representación textual no vacía del parámetro.
     * @throws IllegalArgumentException si el parámetro no existe o su texto está vacío.
     */
    public String requiredParameter(String name) {
        Object value = parameters.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Falta el parámetro obligatorio " + name);
        }
        return value.toString().strip();
    }

    /**
     * Lee la duración anunciada al destinatario, conservando compatibilidad con eventos antiguos.
     *
     * @return minutos positivos de validez del enlace.
     */
    public long expiryMinutes() {
        Object value = parameters.get("expiresInMinutes");
        if (value == null) return 15;
        try {
            long minutes = Long.parseLong(value.toString());
            if (minutes <= 0) throw new NumberFormatException();
            return minutes;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("expiresInMinutes debe ser un entero positivo");
        }
    }

    /**
     * Identifica la solicitud de correo almacenada en el inbox.
     *
     * @return tipo notification.email.requested compartido por productores y consumidores.
     */
    public String eventType() {
        return EVENT_TYPE;
    }

    /**
     * Selecciona el contenido y los parámetros requeridos del correo de identidad.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @see es.ubu.batchdownloader.notification.infrastructure.mail.RoutingNotificationSender
     * @since 0.1.0
     * @version 0.1.0
     * @category Notificaciones
     */
    public enum Template {
        /**
         * Solicita iniciar sesión mediante un token cifrado de un solo uso.
         */
        MAGIC_LINK,
    }

    /**
     * Rechaza campos de texto obligatorios vacíos y elimina sus espacios exteriores.
     *
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @param fieldName Nombre del campo que se incluye en el error de validación.
     * @return texto validado y sin espacios exteriores.
     * @throws IllegalArgumentException si el campo es null o está en blanco.
     */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " no puede estar vacío");
        }
        return value.strip();
    }
}
