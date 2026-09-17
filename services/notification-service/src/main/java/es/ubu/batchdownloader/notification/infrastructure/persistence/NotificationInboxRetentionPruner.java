package es.ubu.batchdownloader.notification.infrastructure.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retira por lotes confirmaciones de correo con más de siete días para acotar el inbox.
 * Conserva intentos fallidos y reservas en curso; una pasada elimina como máximo 500 eventos.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.persistence.JdbcNotificationInbox
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
public class NotificationInboxRetentionPruner {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationInboxRetentionPruner.class);
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter deleted;
    private final Counter failures;

    /**
     * Asocia la política de retención con su almacenamiento, reloj y contadores operativos.
     *
     * @param jdbc Acceso a las filas del inbox que pertenecen a este servicio.
     * @param clock Reloj usado para comparar reservas y registrar instantes en milisegundos UTC.
     * @param meterRegistry Registro de métricas sin identificadores de evento ni contenido del
     *     correo.
     */
    public NotificationInboxRetentionPruner(
            JdbcTemplate jdbc,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.clock = clock;
        deleted = meterRegistry.counter("notification.inbox.retention.deleted");
        failures = meterRegistry.counter("notification.inbox.retention.failures");
    }

    /** Ejecuta una pasada no crítica; un fallo transitorio no reinicia el consumidor. */
    @Scheduled(fixedDelayString = "${notification.retention.interval:PT6H}")
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
     * Selecciona hasta 500 confirmaciones antiguas y las elimina si siguen en PROCESSED.
     *
     * @return número de filas realmente eliminadas.
     * @throws org.springframework.dao.DataAccessException si falla la consulta o alguna
     *     eliminación; las anteriores pueden haberse confirmado.
     */
    public int prune() {
        long cutoff = clock.instant().minus(RETENTION).toEpochMilli();
        List<String> eventIds = jdbc.queryForList(
                """
                SELECT event_id
                FROM notification_inbox
                WHERE status = 'PROCESSED'
                  AND processed_at_epoch_ms IS NOT NULL
                  AND processed_at_epoch_ms < ?
                ORDER BY processed_at_epoch_ms ASC, event_id ASC
                LIMIT ?
                """,
                String.class,
                cutoff,
                BATCH_SIZE);
        int deletedRows = 0;
        for (String eventId : eventIds) {
            deletedRows += jdbc.update(
                    "DELETE FROM notification_inbox WHERE event_id = ? AND status = 'PROCESSED'",
                    eventId);
        }
        return deletedRows;
    }
}
