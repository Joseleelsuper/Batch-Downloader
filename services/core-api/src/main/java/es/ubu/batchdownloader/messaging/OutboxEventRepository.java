package es.ubu.batchdownloader.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Define el contrato de {@code OutboxEventRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    /**
     * Bloquea y devuelve eventos disponibles sin esperar por filas reclamadas por otro proceso.
     *
     * @param now Instante máximo de próximo intento.
     * @param expiredBefore Límite para recuperar reclamaciones abandonadas.
     * @return Colección de eventos que puede reclamar la transacción actual.
     */
    @Query(value = """
            SELECT *
            FROM core_outbox_events
            WHERE published_at IS NULL
              AND next_attempt_at <= :now
              AND (claimed_at IS NULL OR claimed_at < :expiredBefore)
            ORDER BY occurred_at ASC
            LIMIT 50
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEventEntity> findClaimable(
            @Param("now") Instant now,
            @Param("expiredBefore") Instant expiredBefore);

    /** Recupera únicamente una reclamación todavía propiedad del publicador actual. */
    Optional<OutboxEventEntity> findByIdAndClaimToken(UUID id, UUID claimToken);
}
