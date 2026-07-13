package es.ubu.batchdownloader.notification.infrastructure.persistence;

import es.ubu.batchdownloader.notification.application.port.NotificationInbox;
import es.ubu.batchdownloader.notification.config.InboxProperties;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNotificationInbox implements NotificationInbox {

    private static final String STATUS_PROCESSING = "PROCESSING";
    private static final String STATUS_PROCESSED = "PROCESSED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int MAX_ERROR_LENGTH = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final InboxProperties properties;
    private final Clock clock;

    public JdbcNotificationInbox(JdbcTemplate jdbcTemplate, InboxProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ClaimResult claim(UUID eventId, String eventType) {
        long now = clock.millis();
        long leaseUntil = Math.addExact(now, properties.leaseDuration().toMillis());
        int reclaimed = jdbcTemplate.update(
                """
                UPDATE notification_inbox
                   SET status = ?, attempt_count = attempt_count + 1,
                       lease_until_epoch_ms = ?, last_error = NULL
                 WHERE event_id = ?
                   AND (status = ? OR (status = ? AND lease_until_epoch_ms < ?))
                """,
                STATUS_PROCESSING,
                leaseUntil,
                eventId.toString(),
                STATUS_FAILED,
                STATUS_PROCESSING,
                now);
        if (reclaimed == 1) {
            return ClaimResult.ACQUIRED;
        }

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO notification_inbox
                        (event_id, event_type, status, attempt_count,
                         received_at_epoch_ms, lease_until_epoch_ms)
                    VALUES (?, ?, ?, 1, ?, ?)
                    """,
                    eventId.toString(),
                    eventType,
                    STATUS_PROCESSING,
                    now,
                    leaseUntil);
            return ClaimResult.ACQUIRED;
        } catch (DuplicateKeyException duplicate) {
            return existingClaimResult(eventId);
        }
    }

    @Override
    public void markProcessed(UUID eventId) {
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_inbox
                   SET status = ?, processed_at_epoch_ms = ?,
                       lease_until_epoch_ms = NULL, last_error = NULL
                 WHERE event_id = ? AND status = ?
                """,
                STATUS_PROCESSED,
                clock.millis(),
                eventId.toString(),
                STATUS_PROCESSING);
        requireSingleUpdate(updated, eventId, STATUS_PROCESSED);
    }

    @Override
    public void markFailed(UUID eventId, String error) {
        int updated = jdbcTemplate.update(
                """
                UPDATE notification_inbox
                   SET status = ?, lease_until_epoch_ms = NULL, last_error = ?
                 WHERE event_id = ? AND status = ?
                """,
                STATUS_FAILED,
                truncate(error),
                eventId.toString(),
                STATUS_PROCESSING);
        requireSingleUpdate(updated, eventId, STATUS_FAILED);
    }

    private ClaimResult existingClaimResult(UUID eventId) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM notification_inbox WHERE event_id = ?",
                String.class,
                eventId.toString());
        return STATUS_PROCESSED.equals(status) ? ClaimResult.ALREADY_PROCESSED : ClaimResult.BUSY;
    }

    private void requireSingleUpdate(int updated, UUID eventId, String targetStatus) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "No se pudo cambiar el evento " + eventId + " a " + targetStatus);
        }
    }

    private String truncate(String error) {
        String safeError = error == null || error.isBlank() ? "Error no especificado" : error;
        return safeError.length() <= MAX_ERROR_LENGTH
                ? safeError
                : safeError.substring(0, MAX_ERROR_LENGTH);
    }
}
