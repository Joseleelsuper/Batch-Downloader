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
 * Elimina en lotes eventos publicados, mensajes procesados y trabajos terminales fuera de
 * retención, conservando el trabajo pendiente y registrando contadores tras confirmar.
 *
 * @see es.ubu.batchdownloader.messaging.OutboxEventEntity
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y retención
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

    /**
     * Conecta tablas operativas, reloj y gestor de transacciones y registra contadores de filas por
     * tabla y fallos.
     *
     * @param jdbc Acceso SQL al outbox, inbox y trabajos terminales.
     * @param clock Reloj que fecha eventos, reservas, confirmaciones y próximos intentos.
     * @param meterRegistry Registro de contadores de filas eliminadas y fallos de mantenimiento.
     * @param transactionManager Gestor que permite confirmar o revertir conjuntamente una pasada
     *     programada.
     */
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

    /**
     * Ejecuta una pasada dentro de una transacción y publica sus contadores después del commit. Los
     * fallos de acceso a datos se cuentan y registran para permitir otra pasada.
     */
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

    /**
     * Borra hasta BATCH_SIZE filas por tabla bajo los cortes de retención, empezando por las más
     * antiguas; utiliza la transacción del llamador.
     *
     * @return filas eliminadas por cada tabla operativa.
     */
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
                  AND NOT EXISTS (SELECT 1 FROM download_job_storage s WHERE s.job_id=download_jobs.id)
                  AND updated_at < ?
                ORDER BY updated_at ASC, id ASC
                LIMIT ?
                """,
                jobCutoff,
                BATCH_SIZE);
        return new RetentionResult(outbox, inbox, jobs);
    }

    /**
     * Registra el contador de filas eliminadas con una etiqueta estable por tabla.
     *
     * @param meterRegistry Registro de contadores de filas eliminadas y fallos de mantenimiento.
     * @param table Nombre estable de tabla usado como etiqueta de la métrica, sin identificadores
     *     variables.
     * @return contador asociado a esa tabla.
     */
    private static Counter deletedCounter(MeterRegistry meterRegistry, String table) {
        return Counter.builder("operational.retention.deleted")
                .tag("table", table)
                .register(meterRegistry);
    }

    /**
     * Conserva por tabla el resultado de una pasada para contabilizar solo eliminaciones
     * confirmadas.
     *
     * @param outbox Filas de eventos publicados que se eliminaron en la pasada.
     * @param inbox Filas de mensajes procesados que se eliminaron en la pasada.
     * @param downloadJobs Trabajos terminales antiguos que se eliminaron en la pasada.
     * @since 0.1.0
     * @version 0.1.0
     * @category Mensajería y retención
     */
    public record RetentionResult(int outbox, int inbox, int downloadJobs) {
        /**
         * Suma las eliminaciones de las tres tablas detectando desbordamiento entero.
         *
         * @return total de filas eliminadas.
         * @throws ArithmeticException si la suma no cabe en un entero.
         */
        public int total() {
            return Math.addExact(Math.addExact(outbox, inbox), downloadJobs);
        }
    }
}
