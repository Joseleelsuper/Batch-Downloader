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
 * Persiste el sobre de un evento, su disponibilidad y la reserva temporal de publicación; las
 * transiciones liberan la reserva al confirmar o programar un reintento.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.messaging.OutboxDispatcher
 * @see es.ubu.batchdownloader.messaging.OutboxWriter
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y retención
 */
@Entity
@Table(name = "core_outbox_events")
class OutboxEventEntity {
    /**
     * UUID del evento.
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
     * Nombre del tipo de evento.
     */
    @Column(name = "event_type", length = 120, nullable = false)
    private String eventType;
    /**
     * Clave de enrutamiento AMQP.
     */
    @Column(name = "routing_key", length = 160, nullable = false)
    private String routingKey;
    /**
     * Sobre serializado del evento.
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
    /** Identificador efímero de la reclamación que está publicando el evento. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "claim_token", length = 36)
    private UUID claimToken;
    /** Instante desde el que la reclamación permanece activa. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    /**
     * Permite que JPA reconstruya un evento persistido antes de poblar sus campos.
     */
    protected OutboxEventEntity() {}

    /**
     * Crea un evento no publicado y disponible desde su instante de creación para guardarlo con el
     * cambio del agregado.
     *
     * @param id UUID estable del evento, conservado entre los reintentos.
     * @param aggregateType Tipo de agregado que produjo el evento.
     * @param aggregateId UUID del agregado modificado en la misma transacción.
     * @param eventType Tipo de evento que identifica su contrato de carga.
     * @param routingKey Clave AMQP que selecciona los consumidores interesados.
     * @param payload Sobre JSON persistido o carga del evento antes de envolverla, según el punto
     *     del flujo.
     * @param occurredAt Instante de creación del evento y de disponibilidad para el primer intento.
     * @return entidad pendiente todavía sin persistir.
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
     * Fecha la confirmación, elimina el último error y libera la reserva para que el evento deje de
     * ser reclamable.
     *
     * @param now Instante del cambio o corte de disponibilidad que se aplica.
     */
    void markPublished(Instant now) {
        publishedAt = now;
        lastError = null;
        releaseClaim();
    }

    /**
     * Sustituye el sobre por su versión cifrada o saneada sin alterar identidad ni estado de
     * publicación.
     *
     * @param sanitizedPayload Sobre JSON no blanco que sustituye al persistido tras cifrar o
     *     retirar un token.
     * @throws IllegalArgumentException si la representación propuesta es null o blanca.
     */
    void replacePayload(String sanitizedPayload) {
        if (sanitizedPayload == null || sanitizedPayload.isBlank()) {
            throw new IllegalArgumentException("sanitized_payload_required");
        }
        payload = sanitizedPayload;
    }

    /**
     * Incrementa los intentos, aplaza el siguiente envío con espera exponencial de dos a 256
     * segundos y guarda hasta quinientos caracteres de diagnóstico antes de liberar la reserva.
     *
     * @param now Instante del cambio o corte de disponibilidad que se aplica.
     * @param exception Fallo de publicación utilizado para aplazar el intento y registrar
     *     diagnóstico acotado.
     */
    void markFailed(Instant now, RuntimeException exception) {
        attempts++;
        long seconds = Math.min(300, 1L << Math.min(attempts, 8));
        nextAttemptAt = now.plusSeconds(seconds);
        String message = exception.getMessage();
        lastError = (message == null ? exception.getClass().getSimpleName() : message).substring(
                0, Math.min(500, message == null ? exception.getClass().getSimpleName().length() : message.length()));
        releaseClaim();
    }

    /**
     * Asocia el token y el instante a la reserva que habilita la publicación fuera de la
     * transacción.
     *
     * @param token UUID de la reserva que debe conservarse al confirmar su resultado.
     * @param now Instante del cambio o corte de disponibilidad que se aplica.
     */
    void claim(UUID token, Instant now) {
        claimToken = token;
        claimedAt = now;
    }

    /**
     * Elimina token y fecha de reserva al completar la publicación o dejar preparado un reintento.
     */
    private void releaseClaim() {
        claimToken = null;
        claimedAt = null;
    }

    /**
     * Devuelve la identidad estable utilizada por el broker y los consumidores para deduplicar
     * reintentos.
     *
     * @return UUID del evento.
     */
    UUID id() { return id; }
    /**
     * Identifica el contrato de carga y la cabecera de tipo del mensaje AMQP.
     *
     * @return nombre del tipo de evento.
     */
    String eventType() { return eventType; }
    /**
     * Proporciona la clave con la que se publica el evento en el exchange de solicitudes.
     *
     * @return clave de enrutamiento AMQP.
     */
    String routingKey() { return routingKey; }
    /**
     * Devuelve el sobre JSON persistido para migrarlo, enviarlo o retirar su token tras el acuse.
     *
     * @return sobre serializado del evento.
     */
    String payload() { return payload; }
}
