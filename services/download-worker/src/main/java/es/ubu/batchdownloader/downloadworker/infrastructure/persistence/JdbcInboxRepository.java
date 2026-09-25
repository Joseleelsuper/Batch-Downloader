package es.ubu.batchdownloader.downloadworker.infrastructure.persistence;

import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deduplica eventos con una fila por UUID y recupera reservas PROCESSING vencidas mediante una
 * actualización condicional.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.InboxRepository
 * @see
 *     es.ubu.batchdownloader.downloadworker.infrastructure.persistence.DownloadInboxRetentionPruner
 * @since 0.1.0
 * @version 0.1.0
 * @category Adaptadores y persistencia del worker
 */
public class JdbcInboxRepository implements InboxRepository {
    /**
     * Estado {@code jdbc} mantenido por {@code JdbcInboxRepository}.
     */
    private final JdbcTemplate jdbc;
    /**
     * Estado {@code clock} mantenido por {@code JdbcInboxRepository}.
     */
    private final Clock clock;

    /**
     * Conecta las transiciones del inbox con el reloj de reservas.
     *
     * @param jdbc Acceso SQL al inbox local del worker.
     * @param clock Reloj para reservas, confirmaciones y cortes de retención.
     */
    public JdbcInboxRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** El H2 es exclusivo de este proceso: las ejecuciones anteriores ya no tienen escritores. */
    public void recoverAbandoned() {
        jdbc.update("UPDATE download_inbox SET started_at = ? WHERE status = 'PROCESSING'",
                Timestamp.from(Instant.EPOCH));
    }

    /**
     * Inserta una reserva nueva o, ante UUID duplicado, intenta recuperar únicamente una reserva
     * PROCESSING anterior al corte del arrendamiento.
     *
     * @param eventId UUID estable del evento de entrada que se deduplica.
     * @param lease Duración máxima desde started_at antes de recuperar una reserva abandonada.
     * @return true si insertó o recuperó la reserva; false si ya terminó o sigue vigente.
     */
    @Override
    @Transactional
    public boolean tryStart(UUID eventId, Duration lease) {
        Instant now = clock.instant();
        try {
            jdbc.update(
                    "INSERT INTO download_inbox (event_id, status, started_at) VALUES (?, 'PROCESSING', ?)",
                    eventId.toString(),
                    Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            Instant staleBefore = now.minus(lease);
            int reclaimed = jdbc.update(
                    """
                    UPDATE download_inbox
                    SET started_at = ?, completed_at = NULL
                    WHERE event_id = ? AND status = 'PROCESSING' AND started_at < ?
                    """,
                    Timestamp.from(now),
                    eventId.toString(),
                    Timestamp.from(staleBefore));
            return reclaimed == 1;
        }
    }

    /**
     * Marca completado el evento y fecha su confirmación dentro de una transacción.
     *
     * @param eventId UUID estable del evento de entrada que se deduplica.
     */
    @Override
    @Transactional
    public void complete(UUID eventId) {
        jdbc.update(
                "UPDATE download_inbox SET status = 'COMPLETED', completed_at = ?, pending_result = NULL WHERE event_id = ?",
                Timestamp.from(clock.instant()),
                eventId.toString());
    }

    /**
     * Hace recuperable el intento fallido sin perder sus objetos ni el resultado pendiente.
     *
     * @param eventId UUID estable del evento de entrada que se deduplica.
     */
    @Override
    @Transactional
    public void release(UUID eventId) {
        jdbc.update("UPDATE download_inbox SET started_at = ? WHERE event_id = ? AND status = 'PROCESSING'",
                Timestamp.from(Instant.EPOCH), eventId.toString());
    }

    @Override
    public void rememberJob(UUID eventId, UUID jobId) {
        if (jdbc.update("UPDATE download_inbox SET job_id = ? WHERE event_id = ? AND status = 'PROCESSING'",
                jobId.toString(), eventId.toString()) != 1) {
            throw new IllegalStateException("download_inbox_reservation_missing");
        }
    }

    @Override
    public java.util.Set<UUID> trackedJobs() {
        return new java.util.HashSet<>(jdbc.query("SELECT DISTINCT job_id FROM download_inbox WHERE job_id IS NOT NULL",
                (rs, row) -> UUID.fromString(rs.getString(1))));
    }

    @Override
    public void saveReady(UUID eventId, UUID jobId, String eventJson) {
        if (jdbc.update("UPDATE download_inbox SET pending_result = ?, job_id = ? WHERE event_id = ? AND status = 'PROCESSING'",
                eventJson, jobId.toString(), eventId.toString()) != 1) {
            throw new IllegalStateException("download_inbox_reservation_missing");
        }
    }

    @Override
    public String pendingReady(UUID eventId) {
        return jdbc.query("SELECT pending_result FROM download_inbox WHERE event_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, eventId.toString());
    }

    @Override
    public void clearReady(UUID jobId) {
        jdbc.update("UPDATE download_inbox SET pending_result = NULL, job_id = NULL WHERE job_id = ?", jobId.toString());
    }
}
