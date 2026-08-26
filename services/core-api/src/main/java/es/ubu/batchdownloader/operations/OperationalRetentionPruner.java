package es.ubu.batchdownloader.operations;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Poda datos operativos procesados mediante lotes pequeños e idempotentes.
 *
 * <p>Las filas pendientes no aparecen en ninguna sentencia y la auditoría administrativa no se
 * somete a retención.
 */
@Component
public class OperationalRetentionPruner {
    private static final Logger LOGGER = LoggerFactory.getLogger(OperationalRetentionPruner.class);
    private static final Duration MESSAGE_RETENTION = Duration.ofDays(7);
    private static final Duration TERMINAL_JOB_RETENTION = Duration.ofDays(30);
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Counter failures;
    private final Counter deletedOutbox;
    private final Counter deletedInbox;
    private final Counter deletedJobs;

    /** Inicializa el pruner con un reloj inyectable y métricas por tabla. */
    public OperationalRetentionPruner(
            JdbcTemplate jdbc,
            Clock clock,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
        failures = meterRegistry.counter("operational.retention.failures");
        deletedOutbox = deletedCounter(meterRegistry, "core_outbox_events");
        deletedInbox = deletedCounter(meterRegistry, "core_inbox_messages");
        deletedJobs = deletedCounter(meterRegistry, "download_jobs");
    }

    /** Ejecuta el mantenimiento sin convertir un fallo no crítico en un reinicio. */
    @Scheduled(fixedDelayString = "${app.retention.interval:PT6H}")
    public void runScheduled() {
        try {
            RetentionResult result = transactions.execute(status -> prune());
            if (result == null) {
                throw new IllegalStateException("operational_retention_transaction_returned_null");
            }
            deletedOutbox.increment(result.outbox());
            deletedInbox.increment(result.inbox());
            deletedJobs.increment(result.downloadJobs());
            if (result.total() > 0) {
                LOGGER.info(
                        "Operational retention pruned {} rows (outbox={}, inbox={}, jobs={})",
                        result.total(),
                        result.outbox(),
                        result.inbox(),
                        result.downloadJobs());
            }
        } catch (DataAccessException exception) {
            failures.increment();
            LOGGER.warn(
                    "Operational retention could not complete: {}",
                    exception.getClass().getSimpleName());
        }
    }

    /** Elimina como máximo un lote por tabla dentro de una única transacción. */
    public RetentionResult prune() {
        Timestamp messageCutoff = Timestamp.from(clock.instant().minus(MESSAGE_RETENTION));
        Timestamp jobCutoff = Timestamp.from(clock.instant().minus(TERMINAL_JOB_RETENTION));
        int outbox = jdbc.update(
                """
                DELETE FROM core_outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < ?
                ORDER BY published_at ASC, id ASC
                LIMIT ?
                """,
                messageCutoff,
                BATCH_SIZE);
        int inbox = jdbc.update(
                """
                DELETE FROM core_inbox_messages
                WHERE processed_at IS NOT NULL
                  AND processed_at < ?
                ORDER BY processed_at ASC, message_id ASC
                LIMIT ?
                """,
                messageCutoff,
                BATCH_SIZE);
        int jobs = jdbc.update(
                """
                DELETE FROM download_jobs
                WHERE status IN ('READY', 'PARTIAL', 'MANUAL_ONLY', 'FAILED', 'CANCELLED', 'EXPIRED')
                  AND updated_at < ?
                ORDER BY updated_at ASC, id ASC
                LIMIT ?
                """,
                jobCutoff,
                BATCH_SIZE);
        return new RetentionResult(outbox, inbox, jobs);
    }

    private static Counter deletedCounter(MeterRegistry meterRegistry, String table) {
        return Counter.builder("operational.retention.deleted")
                .tag("table", table)
                .register(meterRegistry);
    }

    /** Resultado inmutable de una pasada de retención. */
    public record RetentionResult(int outbox, int inbox, int downloadJobs) {
        /** Devuelve el total de filas podadas. */
        public int total() {
            return Math.addExact(Math.addExact(outbox, inbox), downloadJobs);
        }
    }
}
