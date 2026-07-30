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
 * Gestiona la persistencia y consulta de {@code JdbcInboxRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Inicializa una instancia de {@code JdbcInboxRepository}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     */
    public JdbcInboxRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Implementa {@code tryStart} para {@code JdbcInboxRepository}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param lease Valor de {@code lease} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
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
     * Implementa {@code complete} para {@code JdbcInboxRepository}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     */
    @Override
    @Transactional
    public void complete(UUID eventId) {
        jdbc.update(
                "UPDATE download_inbox SET status = 'COMPLETED', completed_at = ? WHERE event_id = ?",
                Timestamp.from(clock.instant()),
                eventId.toString());
    }

    /**
     * Libera el recurso solicitado mediante {@code release}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     */
    @Override
    @Transactional
    public void release(UUID eventId) {
        jdbc.update(
                "DELETE FROM download_inbox WHERE event_id = ? AND status = 'PROCESSING'",
                eventId.toString());
    }
}
