package es.ubu.batchdownloader.notification.infrastructure.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record NotificationRequestedMessage(
        UUID eventId,
        String type,
        Integer schemaVersion,
        Instant occurredAt,
        String correlationId,
        String causationId,
        Payload payload) {

    public record Payload(
            String recipient,
            String template,
            Map<String, Object> parameters) {}
}
