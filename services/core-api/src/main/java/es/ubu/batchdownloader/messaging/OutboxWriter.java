package es.ubu.batchdownloader.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Registra sobres de eventos en la transacción del caso de uso para que el cambio de datos y su
 * solicitud de publicación sean atómicos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.messaging.OutboxDispatcher
 * @see es.ubu.batchdownloader.messaging.OutboxEventEntity
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y retención
 */
@Component
public class OutboxWriter {
    /**
     * Estado {@code repository} mantenido por {@code OutboxWriter}.
     */
    private final OutboxEventRepository repository;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code OutboxWriter}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code clock} mantenido por {@code OutboxWriter}.
     */
    private final Clock clock;

    /**
     * Conecta la persistencia del outbox con la serialización y el reloj de creación.
     *
     * @param repository Persistencia del outbox que participa en la transacción vigente.
     * @param objectMapper Serializador del sobre de eventos y sus cargas JSON.
     * @param clock Reloj que fecha eventos, reservas, confirmaciones y próximos intentos.
     */
    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Añade identidad, versión de esquema, fecha, correlación y causa a la carga y guarda un evento
     * pendiente en la transacción del llamador.
     *
     * @param aggregateType Tipo de agregado que produjo el evento.
     * @param aggregateId UUID del agregado modificado en la misma transacción.
     * @param eventType Tipo de evento que identifica su contrato de carga.
     * @param routingKey Clave AMQP que selecciona los consumidores interesados.
     * @param correlationId UUID que relaciona el evento con el flujo al que pertenece; puede ser
     *     null.
     * @param causationId UUID del evento que causó este cambio; puede ser null para una acción
     *     inicial.
     * @param payload Carga del contrato de evento que se serializa dentro del sobre común.
     * @return UUID del nuevo evento pendiente.
     * @throws IllegalArgumentException si Jackson no puede serializar la carga.
     */
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
