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

public class JdbcInboxRepository implements InboxRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcInboxRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

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

    @Override
    @Transactional
    public void complete(UUID eventId) {
        jdbc.update(
                "UPDATE download_inbox SET status = 'COMPLETED', completed_at = ? WHERE event_id = ?",
                Timestamp.from(clock.instant()),
                eventId.toString());
    }

    @Override
    @Transactional
    public void release(UUID eventId) {
        jdbc.update(
                "DELETE FROM download_inbox WHERE event_id = ? AND status = 'PROCESSING'",
                eventId.toString());
    }
}
