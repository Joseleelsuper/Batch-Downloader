package es.ubu.batchdownloader.notification.infrastructure.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retira por lotes confirmaciones de correo fuera de la ventana máxima de un día.
 * Retira también fallos antiguos y conserva reservas en curso; una pasada elimina hasta 500 eventos.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.persistence.JdbcNotificationInbox
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
public class NotificationInboxRetentionPruner {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationInboxRetentionPruner.class);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter deleted;
    private final Counter failures;
    private final Duration retention;

    /**
     * Asocia la política de retención con su almacenamiento, reloj y contadores operativos.
     *
     * @param jdbc Acceso a las filas del inbox que pertenecen a este servicio.
     * @param clock Reloj usado para comparar reservas y registrar instantes en milisegundos UTC.
     * @param meterRegistry Registro de métricas sin identificadores de evento ni contenido del
     *     correo.
     * @param interval Cadencia que se descuenta del plazo máximo.
     * @param retention Plazo máximo de deduplicación, hasta veinticuatro horas.
     */
    public NotificationInboxRetentionPruner(
            JdbcTemplate jdbc,
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${notification.retention.interval:PT1M}") Duration interval,
            @Value("${notification.retention.messages:PT24H}") Duration retention) {
        if (interval.isNegative() || interval.isZero() || retention.compareTo(interval) <= 0
                || retention.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("invalid_notification_retention");
        }
        this.retention = retention.minus(interval);
        this.jdbc = jdbc;
        this.clock = clock;
        deleted = meterRegistry.counter("notification.inbox.retention.deleted");
        failures = meterRegistry.counter("notification.inbox.retention.failures");
    }

    /** Ejecuta una pasada no crítica; un fallo transitorio no reinicia el consumidor. */
    @Scheduled(fixedDelayString = "${notification.retention.interval:PT1M}")
    public void runScheduled() {
        try {
            int affected = prune();
            deleted.increment(affected);
            if (affected > 0) {
                LOGGER.info("Notification inbox retention pruned {} rows", affected);
            }
        } catch (DataAccessException exception) {
            failures.increment();
            LOGGER.warn(
                    "Notification inbox retention failed: {}",
                    exception.getClass().getSimpleName());
        }
    }

    /**
     * Selecciona hasta 500 confirmaciones o fallos antiguos y vuelve a comprobar estado y fecha.
     *
     * @return número de filas realmente eliminadas.
     * @throws org.springframework.dao.DataAccessException si falla la consulta o alguna
     *     eliminación; las anteriores pueden haberse confirmado.
     */
    public int prune() {
        long cutoff = clock.instant().minus(retention).toEpochMilli();
        List<String> eventIds = jdbc.queryForList(
                """
                SELECT event_id
                FROM notification_inbox
                WHERE status IN ('PROCESSED', 'FAILED')
                  AND COALESCE(processed_at_epoch_ms, received_at_epoch_ms) < ?
                ORDER BY COALESCE(processed_at_epoch_ms, received_at_epoch_ms) ASC, event_id ASC
                LIMIT ?
                """,
                String.class,
                cutoff,
                BATCH_SIZE);
        int deletedRows = 0;
        for (String eventId : eventIds) {
            deletedRows += jdbc.update(
                    "DELETE FROM notification_inbox WHERE event_id = ? AND status IN ('PROCESSED', 'FAILED') "
                            + "AND COALESCE(processed_at_epoch_ms, received_at_epoch_ms) < ?",
                    eventId, cutoff);
        }
        return deletedRows;
    }
}
