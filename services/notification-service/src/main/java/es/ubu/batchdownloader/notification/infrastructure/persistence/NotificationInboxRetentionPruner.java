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

/** Elimina mensajes procesados antiguos sin tocar fallidos ni reclamaciones activas. */
@Component
public class NotificationInboxRetentionPruner {
    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationInboxRetentionPruner.class);
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter deleted;
    private final Counter failures;

    /** Inicializa el pruner con reloj y métricas inyectables. */
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

    /** Elimina como máximo 500 filas procesadas con más de siete días. */
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
