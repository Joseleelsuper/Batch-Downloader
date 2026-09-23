package es.ubu.batchdownloader.messaging;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persiste el outbox y proporciona consultas de bloqueo y propiedad de reservas que permiten varios
 * publicadores sin esperar unos por otros.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.messaging.OutboxEventEntity
 * @see es.ubu.batchdownloader.messaging.OutboxDispatcher
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y retención
 */
interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
    /**
     * Bloquea hasta cincuenta eventos disponibles con reserva ausente o vencida, omitiendo filas
     * bloqueadas por otros publicadores.
     *
     * @param now Instante del cambio o corte de disponibilidad que se aplica.
     * @param expiredBefore Corte exclusivo de reservas vencidas que se permite reclamar otra vez.
     * @return eventos reclamables en orden de creación; requiere una transacción activa.
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

    /**
     * Comprueba que un evento siga asociado al token del publicador antes de registrar su
     * resultado.
     *
     * @param id UUID estable del evento, conservado entre los reintentos.
     * @param claimToken UUID que acredita que el evento sigue reservado por este publicador.
     * @return evento si conserva esa reserva; vacío si otro publicador la sustituyó.
     */
    Optional<OutboxEventEntity> findByIdAndClaimToken(UUID id, UUID claimToken);
}
