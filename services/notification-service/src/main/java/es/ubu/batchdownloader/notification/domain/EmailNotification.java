package es.ubu.batchdownloader.notification.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record EmailNotification(
        UUID eventId,
        Instant occurredAt,
        String correlationId,
        String causationId,
        String recipient,
        Template template,
        Map<String, Object> parameters) {

    public static final String EVENT_TYPE = "notification.email.requested";
    public static final int SCHEMA_VERSION = 1;

    public EmailNotification {
        eventId = Objects.requireNonNull(eventId, "eventId no puede ser null");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt no puede ser null");
        correlationId = requireText(correlationId, "correlationId");
        recipient = requireText(recipient, "recipient");
        template = Objects.requireNonNull(template, "template no puede ser null");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters no puede ser null"));
    }

    public String requiredParameter(String name) {
        Object value = parameters.get(name);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Falta el parámetro obligatorio " + name);
        }
        return value.toString().strip();
    }

    public String eventType() {
        return EVENT_TYPE;
    }

    public enum Template {
        EMAIL_VERIFICATION,
        PASSWORD_RESET,
        DOWNLOAD_READY,
        DOWNLOAD_FAILED
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " no puede estar vacío");
        }
        return value.strip();
    }
}
