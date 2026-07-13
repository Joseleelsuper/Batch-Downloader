package es.ubu.batchdownloader.notification.infrastructure.messaging;

import es.ubu.batchdownloader.notification.config.RabbitTopologyProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class NotificationRequestedMessageMapper {

    private final RabbitTopologyProperties topology;

    public NotificationRequestedMessageMapper(RabbitTopologyProperties topology) {
        this.topology = topology;
    }

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

    private EmailNotification.Template parseTemplate(String value) {
        try {
            return EmailNotification.Template.valueOf(requireText(value, "payload.template"));
        } catch (IllegalArgumentException exception) {
            throw new InvalidDownloadEventException("Plantilla no soportada: " + value, exception);
        }
    }

    private void validateParameters(EmailNotification.Template template, Map<String, Object> parameters) {
        parameters.forEach(this::validateScalarParameter);
        switch (template) {
            case EMAIL_VERIFICATION, PASSWORD_RESET -> {
                requireParameter(parameters, "username");
                requireParameter(parameters, "token");
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

    private void validateScalarParameter(String key, Object value) {
        requireText(key, "payload.parameters key");
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new InvalidDownloadEventException(
                    "El parámetro " + key + " debe ser string, number o boolean");
        }
    }

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

    private void validateJobId(String jobId) {
        try {
            UUID.fromString(jobId);
        } catch (IllegalArgumentException exception) {
            throw new InvalidDownloadEventException("jobId no es un UUID válido", exception);
        }
    }

    private void validateExpiration(String value) {
        try {
            Instant.parse(value);
        } catch (DateTimeException exception) {
            throw new InvalidDownloadEventException("expiresAt debe usar el formato date-time", exception);
        }
    }

    private String requireFailureCode(Map<String, Object> parameters) {
        Object primary = parameters.get("failureCode");
        if (primary != null && !primary.toString().isBlank()) {
            return primary.toString().strip();
        }
        return requireParameter(parameters, "errorCode");
    }

    private String requireParameter(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new InvalidDownloadEventException("Falta el parámetro obligatorio " + key);
        }
        return value.toString().strip();
    }

    private void requireRoutingKey(String actual) {
        if (!topology.routingKey().equals(actual)) {
            throw new InvalidDownloadEventException(
                    "Routing key incompatible con el evento: " + String.valueOf(actual));
        }
    }

    private <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new InvalidDownloadEventException("Falta el campo obligatorio " + fieldName);
        }
        return value;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new InvalidDownloadEventException("Falta el campo obligatorio " + fieldName);
        }
        return value.strip();
    }
}
