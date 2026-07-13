package es.ubu.batchdownloader.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "core_outbox_events")
class OutboxEventEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    @Column(name = "aggregate_type", length = 80, nullable = false)
    private String aggregateType;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "aggregate_id", length = 36, nullable = false)
    private UUID aggregateId;
    @Column(name = "event_type", length = 120, nullable = false)
    private String eventType;
    @Column(name = "routing_key", length = 160, nullable = false)
    private String routingKey;
    @Column(columnDefinition = "json", nullable = false)
    private String payload;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "last_error", length = 500)
    private String lastError;

    protected OutboxEventEntity() {}

    static OutboxEventEntity pending(
            UUID id,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payload,
            Instant occurredAt) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.id = id;
        entity.aggregateType = aggregateType;
        entity.aggregateId = aggregateId;
        entity.eventType = eventType;
        entity.routingKey = routingKey;
        entity.payload = payload;
        entity.occurredAt = occurredAt;
        entity.nextAttemptAt = occurredAt;
        return entity;
    }

    void markPublished(Instant now) {
        publishedAt = now;
        lastError = null;
    }

    void markFailed(Instant now, RuntimeException exception) {
        attempts++;
        long seconds = Math.min(300, 1L << Math.min(attempts, 8));
        nextAttemptAt = now.plusSeconds(seconds);
        String message = exception.getMessage();
        lastError = (message == null ? exception.getClass().getSimpleName() : message).substring(
                0, Math.min(500, message == null ? exception.getClass().getSimpleName().length() : message.length()));
    }

    UUID id() { return id; }
    String eventType() { return eventType; }
    String routingKey() { return routingKey; }
    String payload() { return payload; }
}
