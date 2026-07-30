package es.ubu.batchdownloader.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Define el contrato de {@code OutboxEventRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    /**
     * Busca el resultado solicitado mediante {@code
     * findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByOccurredAtAsc}.
     *
     * @param now Valor de {@code now} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    List<OutboxEventEntity> findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByOccurredAtAsc(Instant now);
}
