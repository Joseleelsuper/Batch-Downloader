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
 * Persiste reservas de eventos de correo con caducidad para coordinar consumidores concurrentes.
 *
 * Recupera intentos fallidos o reservas vencidas, incrementa sus intentos y confirma únicamente
 * eventos en PROCESSING. No confirma el envío por el mero hecho de haber reservado un evento.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.application.port.NotificationInbox
 * @see es.ubu.batchdownloader.notification.config.InboxProperties
 * @see es.ubu.batchdownloader.notification.application.ProcessEmailNotification
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Repository
public class JdbcNotificationInbox implements NotificationInbox {

    /**
     * Reserva adquirida cuyo resultado aún no ha sido confirmado.
     */
    private static final String STATUS_PROCESSING = "PROCESSING";
    /**
     * Evento cuyo envío ya se confirmó y debe ignorarse si vuelve a llegar.
     */
    private static final String STATUS_PROCESSED = "PROCESSED";
    /**
     * Intento fallido que puede reservarse de nuevo en otra entrega.
     */
    private static final String STATUS_FAILED = "FAILED";
    /**
     * Máximo de caracteres UTF-16 del error almacenado en el inbox.
     */
    private static final int MAX_ERROR_LENGTH = 1000;

    /**
     * Acceso JDBC a la base de datos propietaria del inbox.
     */
    private final JdbcTemplate jdbcTemplate;
    /**
     * Duración usada para calcular la caducidad de cada reserva.
     */
    private final InboxProperties properties;
    /**
     * Reloj usado para comparar reservas y registrar instantes en milisegundos UTC.
     */
    private final Clock clock;

    /**
     * Asocia el almacenamiento del inbox con la duración de reservas y el reloj de expiración.
     *
     * @param jdbcTemplate Acceso JDBC a la base de datos propietaria del inbox.
     * @param properties Duración positiva de la reserva de procesamiento.
     * @param clock Reloj usado para comparar reservas y registrar instantes en milisegundos UTC.
     */
    public JdbcNotificationInbox(JdbcTemplate jdbcTemplate, InboxProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Recupera de forma transaccional una reserva fallida o vencida; si es nueva, intenta
     * insertarla.
     * Una colisión de clave distingue un evento ya confirmado de una reserva todavía ocupada.
     *
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @param eventType Tipo de evento almacenado junto a la reserva del inbox.
     * @return resultado de la reserva; nunca concede permiso por una simple lectura previa.
     * @see es.ubu.batchdownloader.notification.application.port.NotificationInbox
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
     * Confirma una única reserva PROCESSING, guarda el instante del envío y elimina el error y la
     * caducidad.
     *
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @throws IllegalStateException si no se actualiza exactamente una reserva en procesamiento.
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
     * Marca FAILED una reserva PROCESSING, elimina su caducidad y guarda hasta 1000 caracteres del
     * error.
     *
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @param error Descripción del fallo persistido; puede estar vacía y se acota antes de
     *     guardarla.
     *
     * @throws IllegalStateException si no se actualiza exactamente una reserva en procesamiento.
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
     * Consulta el evento que provocó una colisión al insertar una reserva.
     *
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @return ALREADY_PROCESSED si está confirmado; BUSY para cualquier otro estado.
     */
    private ClaimResult existingClaimResult(UUID eventId) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM notification_inbox WHERE event_id = ?",
                String.class,
                eventId.toString());
        return STATUS_PROCESSED.equals(status) ? ClaimResult.ALREADY_PROCESSED : ClaimResult.BUSY;
    }

    /**
     * Impide aceptar una confirmación o un fallo que no haya modificado la reserva esperada.
     *
     * @param updated Número de filas afectadas por la transición del evento.
     * @param eventId UUID del evento; identifica la misma entrega en todos sus reintentos.
     * @param targetStatus Estado final solicitado, utilizado en el diagnóstico de una transición
     *     fallida.
     *
     * @throws IllegalStateException si la transición no afecta exactamente a una fila.
     */
    private void requireSingleUpdate(int updated, UUID eventId, String targetStatus) {
        if (updated != 1) {
            throw new IllegalStateException(
                    "No se pudo cambiar el evento " + eventId + " a " + targetStatus);
        }
    }

    /**
     * Adapta el error al tamaño de almacenamiento y sustituye los mensajes ausentes.
     *
     * @param error Descripción del fallo persistido; puede estar vacía y se acota antes de
     *     guardarla.
     *
     * @return texto de hasta 1000 caracteres; Error no especificado si falta contenido.
     */
    private String truncate(String error) {
        String safeError = error == null || error.isBlank() ? "Error no especificado" : error;
        return safeError.length() <= MAX_ERROR_LENGTH
                ? safeError
                : safeError.substring(0, MAX_ERROR_LENGTH);
    }
}
