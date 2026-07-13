package es.ubu.batchdownloader.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {
    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public UUID append(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String routingKey,
            UUID correlationId,
            UUID causationId,
            Object payload) {
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = clock.instant();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("type", eventType);
        envelope.put("schemaVersion", 1);
        envelope.put("occurredAt", occurredAt);
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", causationId);
        envelope.put("payload", payload);
        try {
            repository.save(OutboxEventEntity.pending(
                    eventId, aggregateType, aggregateId, eventType, routingKey,
                    objectMapper.writeValueAsString(envelope), occurredAt));
            return eventId;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("event_payload_not_serializable", exception);
        }
    }
}
