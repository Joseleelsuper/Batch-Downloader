package es.ubu.batchdownloader.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Implementa el componente {@code OutboxEventEntity}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Entity
@Table(name = "core_outbox_events")
class OutboxEventEntity {
    /**
     * Estado {@code id} mantenido por {@code OutboxEventEntity}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    /**
     * Estado {@code aggregateType} mantenido por {@code OutboxEventEntity}.
     */
    @Column(name = "aggregate_type", length = 80, nullable = false)
    private String aggregateType;
    /**
     * Estado {@code aggregateId} mantenido por {@code OutboxEventEntity}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "aggregate_id", length = 36, nullable = false)
    private UUID aggregateId;
    /**
     * Estado {@code eventType} mantenido por {@code OutboxEventEntity}.
     */
    @Column(name = "event_type", length = 120, nullable = false)
    private String eventType;
    /**
     * Estado {@code routingKey} mantenido por {@code OutboxEventEntity}.
     */
    @Column(name = "routing_key", length = 160, nullable = false)
    private String routingKey;
    /**
     * Estado {@code payload} mantenido por {@code OutboxEventEntity}.
     */
    @Column(columnDefinition = "json", nullable = false)
    private String payload;
    /**
     * Estado {@code occurredAt} mantenido por {@code OutboxEventEntity}.
     */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    /**
     * Estado {@code publishedAt} mantenido por {@code OutboxEventEntity}.
     */
    @Column(name = "published_at")
    private Instant publishedAt;
    /**
     * Estado {@code attempts} mantenido por {@code OutboxEventEntity}.
     */
    @Column(nullable = false)
    private int attempts;
    /**
     * Estado {@code nextAttemptAt} mantenido por {@code OutboxEventEntity}.
     */
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    /**
     * Estado {@code lastError} mantenido por {@code OutboxEventEntity}.
     */
    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * Inicializa una instancia de {@code OutboxEventEntity}.
     */
    protected OutboxEventEntity() {}

    /**
     * Ejecuta la operación {@code pending}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param aggregateType Valor de {@code aggregateType} utilizado por la operación.
     * @param aggregateId Identificador de {@code aggregate} utilizado por la operación.
     * @param eventType Valor de {@code eventType} utilizado por la operación.
     * @param routingKey Valor de {@code routingKey} utilizado por la operación.
     * @param payload Carga de datos recibida por la operación.
     * @param occurredAt Valor de {@code occurredAt} utilizado por la operación.
     * @return Resultado producido por {@code pending}.
     */
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

    /**
     * Marca el recurso solicitado mediante {@code markPublished}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     */
    void markPublished(Instant now) {
        publishedAt = now;
        lastError = null;
    }

    /**
     * Marca el recurso solicitado mediante {@code markFailed}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     * @param exception Valor de {@code exception} utilizado por la operación.
     */
    void markFailed(Instant now, RuntimeException exception) {
        attempts++;
        long seconds = Math.min(300, 1L << Math.min(attempts, 8));
        nextAttemptAt = now.plusSeconds(seconds);
        String message = exception.getMessage();
        lastError = (message == null ? exception.getClass().getSimpleName() : message).substring(
                0, Math.min(500, message == null ? exception.getClass().getSimpleName().length() : message.length()));
    }

    /**
     * Ejecuta la operación {@code id}.
     *
     * @return Resultado producido por {@code id}.
     */
    UUID id() { return id; }
    /**
     * Ejecuta la operación {@code eventType}.
     *
     * @return Resultado producido por {@code eventType}.
     */
    String eventType() { return eventType; }
    /**
     * Ejecuta la operación {@code routingKey}.
     *
     * @return Resultado producido por {@code routingKey}.
     */
    String routingKey() { return routingKey; }
    /**
     * Ejecuta la operación {@code payload}.
     *
     * @return Resultado producido por {@code payload}.
     */
    String payload() { return payload; }
}
