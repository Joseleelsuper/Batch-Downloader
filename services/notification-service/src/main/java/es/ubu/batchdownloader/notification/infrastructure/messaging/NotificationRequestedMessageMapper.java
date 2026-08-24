package es.ubu.batchdownloader.notification.infrastructure.messaging;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import es.ubu.batchdownloader.notification.config.RabbitTopologyProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Transforma los datos gestionados por {@code NotificationRequestedMessageMapper}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class NotificationRequestedMessageMapper {

    /**
     * Estado {@code topology} mantenido por {@code NotificationRequestedMessageMapper}.
     */
    private final RabbitTopologyProperties topology;

    /**
     * Inicializa una instancia de {@code NotificationRequestedMessageMapper}.
     *
     * @param topology Valor de {@code topology} utilizado por la operación.
     */
    public NotificationRequestedMessageMapper(RabbitTopologyProperties topology) {
        this.topology = topology;
    }

    /**
     * Transforma el valor recibido mediante {@code map}.
     *
     * @param message Mensaje que debe procesarse.
     * @param routingKey Valor de {@code routingKey} utilizado por la operación.
     * @return Resultado producido por {@code map}.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    public EmailNotification map(NotificationRequestedMessage message, String routingKey) {
        if (message == null) {
            throw new InvalidDownloadEventException("El evento no puede ser null");
        }
        requireRoutingKey(routingKey);
        if (!EmailNotification.EVENT_TYPE.equals(message.type())) {
            throw new InvalidDownloadEventException("Tipo de evento no soportado: " + message.type());
        }
        if (message.schemaVersion() == null
                || message.schemaVersion() != EmailNotification.SCHEMA_VERSION) {
            throw new InvalidDownloadEventException(
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
                    template,
                    parameters);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidDownloadEventException("Evento de notificación inválido", exception);
        }
    }

    /**
     * Analiza el contenido recibido mediante {@code parseTemplate}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code parseTemplate}.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private EmailNotification.Template parseTemplate(String value) {
        try {
            return EmailNotification.Template.valueOf(requireText(value, "payload.template"));
        } catch (IllegalArgumentException exception) {
            throw new InvalidDownloadEventException("Plantilla no soportada: " + value, exception);
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validateParameters}.
     *
     * @param template Valor de {@code template} utilizado por la operación.
     * @param parameters Valor de {@code parameters} utilizado por la operación.
     */
    private void validateParameters(EmailNotification.Template template, Map<String, Object> parameters) {
        parameters.forEach(this::validateScalarParameter);
        switch (template) {
            case EMAIL_VERIFICATION, PASSWORD_RESET -> {
                requireParameter(parameters, "username");
                String token = requireParameter(parameters, "token");
                if (!NotificationTokenEnvelope.isVersion1(token)) {
                    throw new InvalidDownloadEventException(
                            "payload.parameters.token debe usar el sobre enc:v1");
                }
            }
            case DOWNLOAD_READY -> {
                validateJobId(requireParameter(parameters, "jobId"));
                validateExpiration(requireParameter(parameters, "expiresAt"));
            }
            case DOWNLOAD_FAILED -> {
                validateJobId(requireParameter(parameters, "jobId"));
                requireFailureCode(parameters);
                requireParameter(parameters, "failureMessage");
            }
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validateScalarParameter}.
     *
     * @param key Valor de {@code key} utilizado por la operación.
     * @param value Valor que debe procesarse.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private void validateScalarParameter(String key, Object value) {
        requireText(key, "payload.parameters key");
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new InvalidDownloadEventException(
                    "El parámetro " + key + " debe ser string, number o boolean");
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validateEmail}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code validateEmail}.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private String validateEmail(String value) {
        String recipient = requireText(value, "payload.recipient");
        try {
            InternetAddress address = new InternetAddress(recipient, true);
            address.validate();
            if (!recipient.equals(address.getAddress())) {
                throw new InvalidDownloadEventException("payload.recipient debe contener una sola dirección");
            }
            return recipient;
        } catch (AddressException exception) {
            throw new InvalidDownloadEventException("payload.recipient no es un email válido", exception);
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validateJobId}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private void validateJobId(String jobId) {
        try {
            UUID.fromString(jobId);
        } catch (IllegalArgumentException exception) {
            throw new InvalidDownloadEventException("jobId no es un UUID válido", exception);
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validateExpiration}.
     *
     * @param value Valor que debe procesarse.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private void validateExpiration(String value) {
        try {
            Instant.parse(value);
        } catch (DateTimeException exception) {
            throw new InvalidDownloadEventException("expiresAt debe usar el formato date-time", exception);
        }
    }

    /**
     * Ejecuta la operación {@code requireFailureCode}.
     *
     * @param parameters Valor de {@code parameters} utilizado por la operación.
     * @return Resultado producido por {@code requireFailureCode}.
     */
    private String requireFailureCode(Map<String, Object> parameters) {
        Object primary = parameters.get("failureCode");
        if (primary != null && !primary.toString().isBlank()) {
            return primary.toString().strip();
        }
        return requireParameter(parameters, "errorCode");
    }

    /**
     * Ejecuta la operación {@code requireParameter}.
     *
     * @param parameters Valor de {@code parameters} utilizado por la operación.
     * @param key Valor de {@code key} utilizado por la operación.
     * @return Resultado producido por {@code requireParameter}.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private String requireParameter(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new InvalidDownloadEventException("Falta el parámetro obligatorio " + key);
        }
        return value.toString().strip();
    }

    /**
     * Ejecuta la operación {@code requireRoutingKey}.
     *
     * @param actual Valor de {@code actual} utilizado por la operación.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private void requireRoutingKey(String actual) {
        if (!topology.routingKey().equals(actual)) {
            throw new InvalidDownloadEventException(
                    "Routing key incompatible con el evento: " + String.valueOf(actual));
        }
    }

    /**
     * Ejecuta la operación {@code requireNonNull}.
     *
     * @param <T> Parámetro de tipo utilizado por la operación.
     * @param value Valor que debe procesarse.
     * @param fieldName Valor de {@code fieldName} utilizado por la operación.
     * @return Resultado producido por {@code requireNonNull}.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new InvalidDownloadEventException("Falta el campo obligatorio " + fieldName);
        }
        return value;
    }

    /**
     * Ejecuta la operación {@code requireText}.
     *
     * @param value Valor que debe procesarse.
     * @param fieldName Valor de {@code fieldName} utilizado por la operación.
     * @return Resultado producido por {@code requireText}.
     * @throws InvalidDownloadEventException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidDownloadEventException("Falta el campo obligatorio " + fieldName);
        }
        return value.strip();
    }
}
