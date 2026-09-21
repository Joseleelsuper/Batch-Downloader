package es.ubu.batchdownloader.notification.infrastructure.messaging;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import es.ubu.batchdownloader.notification.config.RabbitTopologyProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Valida el sobre recibido de RabbitMQ y lo convierte en una solicitud de correo de dominio.
 *
 * Comprueba ruta, tipo, versión, destinatario único, escalares y requisitos de la plantilla. Los
 * tokens de identidad deben llegar como sobres enc:v1; esta conversión no los descifra.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.domain.EmailNotification
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.RabbitNotificationRequestedListener
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.messaging.NotificationRequestedMessage
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
public class NotificationRequestedMessageMapper {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;<>]+@[^\\s@,;<>]+$");
    private static final Pattern LOCALE = Pattern.compile(
            "^[A-Za-z]{2,12}(?:[-_][A-Za-z0-9]{2,12})*$");

    /**
     * Nombres configurados de exchange, colas y claves de enrutamiento.
     */
    private final RabbitTopologyProperties topology;

    /**
     * Asocia la validación de entrada con la clave de enrutamiento aceptada por el consumidor.
     *
     * @param topology Nombres configurados de exchange, colas y claves de enrutamiento.
     */
    public NotificationRequestedMessageMapper(RabbitTopologyProperties topology) {
        this.topology = topology;
    }

    /**
     * Comprueba el contrato del evento y sus parámetros antes de permitir reservarlo o enviarlo.
     *
     * @param message Sobre JSON deserializado desde RabbitMQ; aún no es un evento de dominio
     *     válido.
     *
     * @param routingKey Clave de enrutamiento recibida de RabbitMQ.
     * @return solicitud de correo con invariantes de dominio y parámetros de plantilla comprobados.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     el sobre, destinatario, plantilla, ruta o parámetros incumplen el contrato.
     */
    public EmailNotification map(NotificationRequestedMessage message, String routingKey) {
        if (message == null) {
            throw new InvalidNotificationEventException("El evento no puede ser null");
        }
        requireRoutingKey(routingKey);
        if (!EmailNotification.EVENT_TYPE.equals(message.type())) {
            throw new InvalidNotificationEventException("Tipo de evento no soportado: " + message.type());
        }
        if (message.schemaVersion() == null
                || message.schemaVersion() != EmailNotification.SCHEMA_VERSION) {
            throw new InvalidNotificationEventException(
                    "Versión de esquema no soportada: " + message.schemaVersion());
        }

        NotificationRequestedMessage.Payload payload = requireNonNull(message.payload(), "payload");
        EmailNotification.Template template = parseTemplate(payload.template());
        Map<String, Object> parameters = requireNonNull(payload.parameters(), "payload.parameters");
        validateParameters(template, parameters);
        String recipient = validateEmail(payload.recipient());

        try {
            return new EmailNotification(
                    requireNonNull(message.eventId(), "eventId"),
                    requireNonNull(message.occurredAt(), "occurredAt"),
                    requireText(message.correlationId(), "correlationId"),
                    message.causationId(),
                    recipient,
                    validateLocale(payload.locale()),
                    template,
                    parameters);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidNotificationEventException("Evento de notificación inválido", exception);
        }
    }

    /**
     * Convierte el nombre exacto de una plantilla admitida, rechazando nombres ausentes o
     * desconocidos.
     *
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @return plantilla de identidad reconocida.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     no se reconoce el nombre de la plantilla.
     */
    private EmailNotification.Template parseTemplate(String value) {
        try {
            return EmailNotification.Template.valueOf(requireText(value, "payload.template"));
        } catch (IllegalArgumentException exception) {
            throw new InvalidNotificationEventException("Plantilla no soportada: " + value, exception);
        }
    }

    /**
     * Exige escalares y el sobre cifrado del token de identidad.
     *
     * @param template Finalidad del correo, que determina sus parámetros y proveedor.
     * @param parameters Valores escalares de la plantilla; los tokens de identidad llegan cifrados.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     faltan datos, tienen un formato inválido o el token no usa enc:v1.
     */
    private void validateParameters(EmailNotification.Template template, Map<String, Object> parameters) {
        parameters.forEach(this::validateScalarParameter);
        switch (template) {
            case MAGIC_LINK -> {
                String token = requireParameter(parameters, "token");
                if (!NotificationTokenEnvelope.isVersion1(token)) {
                    throw new InvalidNotificationEventException(
                            "payload.parameters.token debe usar el sobre enc:v1");
                }
                Object expiry = parameters.get("expiresInMinutes");
                if (expiry != null) {
                    try {
                        if (Long.parseLong(expiry.toString()) <= 0) throw new NumberFormatException();
                    } catch (NumberFormatException exception) {
                        throw new InvalidNotificationEventException(
                                "payload.parameters.expiresInMinutes debe ser positivo", exception);
                    }
                }
            }
        }
    }

    /**
     * Rechaza claves vacías y valores que no sean texto, número o booleano.
     *
     * @param key Nombre no vacío del parámetro que se examina.
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     la clave está vacía o el valor es null, una colección o un objeto.
     */
    private void validateScalarParameter(String key, Object value) {
        requireText(key, "payload.parameters key");
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new InvalidNotificationEventException(
                    "El parámetro " + key + " debe ser string, number o boolean");
        }
    }

    /**
     * Exige una única dirección de correo válida sin nombre visible ni sintaxis de lista.
     *
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @return dirección validada sin espacios exteriores.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     la dirección falta, es inválida o incluye contenido adicional.
     */
    private String validateEmail(String value) {
        String recipient = requireText(value, "payload.recipient");
        if (recipient.length() > 320 || !EMAIL.matcher(recipient).matches()) {
            throw new InvalidNotificationEventException("payload.recipient no es un email válido");
        }
        return recipient;
    }

    private String validateLocale(String value) {
        String locale = value == null || value.isBlank() ? "es" : value.strip();
        if (!LOCALE.matcher(locale).matches()) {
            throw new InvalidNotificationEventException("payload.locale no es válido");
        }
        return locale;
    }

    /**
     * Obtiene un parámetro escalar obligatorio como texto sin espacios exteriores.
     *
     * @param parameters Valores escalares de la plantilla; los tokens de identidad llegan cifrados.
     * @param key Nombre no vacío del parámetro que se examina.
     * @return texto no vacío del parámetro solicitado.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     el parámetro falta o su representación textual está vacía.
     */
    private String requireParameter(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new InvalidNotificationEventException("Falta el parámetro obligatorio " + key);
        }
        return value.toString().strip();
    }

    /**
     * Impide aceptar mensajes recibidos con una clave ajena a la suscripción configurada.
     *
     * @param actual Clave recibida, que debe coincidir exactamente con la configuración.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     la clave no coincide exactamente con la configuración.
     */
    private void requireRoutingKey(String actual) {
        if (!topology.routingKey().equals(actual)) {
            throw new InvalidNotificationEventException(
                    "Routing key incompatible con el evento: " + String.valueOf(actual));
        }
    }

    /**
     * Exige la presencia de un campo del sobre, conservando su tipo.
     *
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @param fieldName Nombre del campo que se incluye en el error de validación.
     * @param <T> Tipo del campo validado, que se devuelve sin conversión.
     * @return valor original no nulo.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     el campo es null.
     */
    private <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new InvalidNotificationEventException("Falta el campo obligatorio " + fieldName);
        }
        return value;
    }

    /**
     * Exige contenido textual en un campo obligatorio y elimina espacios exteriores.
     *
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @param fieldName Nombre del campo que se incluye en el error de validación.
     * @return texto no vacío del campo.
     * @throws
     *     es.ubu.batchdownloader.notification.infrastructure.messaging.InvalidNotificationEventException si
     *     el campo es null o está en blanco.
     */
    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidNotificationEventException("Falta el campo obligatorio " + fieldName);
        }
        return value.strip();
    }
}
