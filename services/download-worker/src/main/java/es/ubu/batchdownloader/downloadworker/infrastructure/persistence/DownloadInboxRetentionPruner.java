package es.ubu.batchdownloader.downloadworker.infrastructure.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Elimina idempotencias completadas antiguas sin tocar reclamaciones activas. */
@Component
public class DownloadInboxRetentionPruner {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadInboxRetentionPruner.class);
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter deleted;
    private final Counter failures;

    /** Inicializa el pruner con reloj y métricas inyectables. */
    public DownloadInboxRetentionPruner(
            JdbcTemplate jdbc,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.clock = clock;
        deleted = meterRegistry.counter("download.worker.inbox.retention.deleted");
        failures = meterRegistry.counter("download.worker.inbox.retention.failures");
    }

    /** Ejecuta una pasada no crítica; un fallo transitorio no reinicia el worker. */
    @Scheduled(fixedDelayString = "${download-worker.retention.interval:PT6H}")
    public void runScheduled() {
        try {
            int affected = prune();
            deleted.increment(affected);
            if (affected > 0) {
                LOGGER.info("Download inbox retention pruned {} rows", affected);
            }
        } catch (DataAccessException exception) {
            failures.increment();
            LOGGER.warn(
                    "Download inbox retention failed: {}",
                    exception.getClass().getSimpleName());
        }
    }

    /** Elimina como máximo 500 filas completadas con más de siete días. */
    public int prune() {
        List<String> eventIds = jdbc.queryForList(
                """
                SELECT event_id
                FROM download_inbox
                WHERE status = 'COMPLETED'
                  AND completed_at IS NOT NULL
                  AND completed_at < ?
                ORDER BY completed_at ASC, event_id ASC
                LIMIT ?
                """,
                String.class,
                Timestamp.from(clock.instant().minus(RETENTION)),
                BATCH_SIZE);
        int deletedRows = 0;
        for (String eventId : eventIds) {
            deletedRows += jdbc.update(
                    "DELETE FROM download_inbox WHERE event_id = ? AND status = 'COMPLETED'",
                    eventId);
        }
        return deletedRows;
    }
}
