package es.ubu.batchdownloader.notification.infrastructure.persistence;

import es.ubu.batchdownloader.notification.application.port.NotificationInbox;
import es.ubu.batchdownloader.notification.config.InboxProperties;
import java.time.Clock;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa el componente {@code JdbcNotificationInbox}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class JdbcNotificationInbox implements NotificationInbox {

    /**
     * Constante que define {@code STATUS_PROCESSING}.
     */
    private static final String STATUS_PROCESSING = "PROCESSING";
    /**
     * Constante que define {@code STATUS_PROCESSED}.
     */
    private static final String STATUS_PROCESSED = "PROCESSED";
    /**
     * Constante que define {@code STATUS_FAILED}.
     */
    private static final String STATUS_FAILED = "FAILED";
    /**
     * Constante que define {@code MAX_ERROR_LENGTH}.
     */
    private static final int MAX_ERROR_LENGTH = 1000;

    /**
     * Estado {@code jdbcTemplate} mantenido por {@code JdbcNotificationInbox}.
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * Estado {@code properties} mantenido por {@code JdbcNotificationInbox}.
     */
    private final InboxProperties properties;
    /**
     * Estado {@code clock} mantenido por {@code JdbcNotificationInbox}.
     */
    private final Clock clock;

    /**
     * Inicializa una instancia de {@code JdbcNotificationInbox}.
     *
     * @param jdbcTemplate Valor de {@code jdbcTemplate} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     */
    public JdbcNotificationInbox(JdbcTemplate jdbcTemplate, InboxProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Reserva el elemento solicitado mediante {@code claim}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param eventType Valor de {@code eventType} utilizado por la operación.
     * @return Resultado producido por {@code claim}.
     */
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

    /**
     * Marca el recurso solicitado mediante {@code markProcessed}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     */
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

    /**
     * Marca el recurso solicitado mediante {@code markFailed}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param error Valor de {@code error} utilizado por la operación.
     */
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

    /**
     * Ejecuta la operación {@code existingClaimResult}.
     *
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @return Resultado producido por {@code existingClaimResult}.
     */
    private ClaimResult existingClaimResult(UUID eventId) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM notification_inbox WHERE event_id = ?",
                String.class,
                eventId.toString());
        return STATUS_PROCESSED.equals(status) ? ClaimResult.ALREADY_PROCESSED : ClaimResult.BUSY;
    }

    /**
     * Ejecuta la operación {@code requireSingleUpdate}.
     *
     * @param updated Valor de {@code updated} utilizado por la operación.
     * @param eventId Identificador de {@code event} utilizado por la operación.
     * @param targetStatus Valor de {@code targetStatus} utilizado por la operación.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    private void requireSingleUpdate(int updated, UUID eventId, String targetStatus) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "No se pudo cambiar el evento " + eventId + " a " + targetStatus);
        }
    }

    /**
     * Ejecuta la operación {@code truncate}.
     *
     * @param error Valor de {@code error} utilizado por la operación.
     * @return Resultado producido por {@code truncate}.
     */
    private String truncate(String error) {
        String safeError = error == null || error.isBlank() ? "Error no especificado" : error;
        return safeError.length() <= MAX_ERROR_LENGTH
                ? safeError
                : safeError.substring(0, MAX_ERROR_LENGTH);
    }
}
