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
 * Publica los datos gestionados por {@code OutboxWriter}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Inicializa una instancia de {@code OutboxWriter}.
     *
     * @param repository Repositorio utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     */
    public OutboxWriter(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Ejecuta la operación {@code append}.
     *
     * @param aggregateType Valor de {@code aggregateType} utilizado por la operación.
     * @param aggregateId Identificador de {@code aggregate} utilizado por la operación.
     * @param eventType Valor de {@code eventType} utilizado por la operación.
     * @param routingKey Valor de {@code routingKey} utilizado por la operación.
     * @param correlationId Identificador de {@code correlation} utilizado por la operación.
     * @param causationId Identificador de {@code causation} utilizado por la operación.
     * @param payload Carga de datos recibida por la operación.
     * @return Resultado producido por {@code append}.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
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
