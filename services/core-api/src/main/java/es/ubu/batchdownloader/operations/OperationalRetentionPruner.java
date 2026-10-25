package es.ubu.batchdownloader.operations;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
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
    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Counter failures;
    private final Counter deletedOutbox;
    private final Counter deletedInbox;
    private final Counter deletedJobs;
    private final Counter deletedTokens;
    private final Counter deletedSessions;
    private final Counter deletedAudit;
    private final Duration messageRetention;
    private final Duration auditRetention;
    private final Duration magicLinkTtl;

    /**
     * Conecta tablas operativas, reloj y gestor de transacciones y registra contadores de filas por
     * tabla y fallos.
     *
     * @param jdbc Acceso SQL al outbox, inbox y trabajos terminales.
     * @param clock Reloj que fecha eventos, reservas, confirmaciones y próximos intentos.
     * @param meterRegistry Registro de contadores de filas eliminadas y fallos de mantenimiento.
     * @param transactionManager Gestor que permite confirmar o revertir conjuntamente una pasada
     *     programada.
     * @param interval Cadencia de limpieza descontada del plazo máximo de conservación.
     * @param messageRetention Ventana de deduplicación, como máximo veinticuatro horas.
     * @param auditRetention Plazo máximo de auditoría, como máximo veinticuatro horas.
     * @param magicLinkTtl Vigencia del enlace, también aplicada a correos aún no publicados.
     */
    public OperationalRetentionPruner(
            JdbcTemplate jdbc,
            Clock clock,
            MeterRegistry meterRegistry,
            PlatformTransactionManager transactionManager,
            @Value("${app.retention.interval:PT1M}") Duration interval,
            @Value("${app.retention.messages:PT24H}") Duration messageRetention,
            @Value("${app.retention.audit:PT24H}") Duration auditRetention,
            @Value("${app.auth.magic-link-ttl}") Duration magicLinkTtl) {
        this.messageRetention = effectiveRetention(messageRetention, interval);
        this.auditRetention = effectiveRetention(auditRetention, interval);
        this.magicLinkTtl = magicLinkTtl;
        this.jdbc = jdbc;
        this.clock = clock;
        transactions = new TransactionTemplate(transactionManager);
        failures = meterRegistry.counter("operational.retention.failures");
        deletedOutbox = deletedCounter(meterRegistry, "core_outbox_events");
        deletedInbox = deletedCounter(meterRegistry, "core_inbox_messages");
        deletedJobs = deletedCounter(meterRegistry, "download_jobs");
        deletedTokens = deletedCounter(meterRegistry, "identity_tokens");
        deletedSessions = deletedCounter(meterRegistry, "SPRING_SESSION");
        deletedAudit = deletedCounter(meterRegistry, "admin_audit_logs");
    }

    /**
     * Ejecuta una pasada dentro de una transacción y publica sus contadores después del commit. Los
     * fallos de acceso a datos se cuentan y registran para permitir otra pasada.
     */
    @Scheduled(fixedDelayString = "${app.retention.interval:PT1M}")
    public void runScheduled() {
        try {
            RetentionResult result = transactions.execute(status -> prune());
            if (result == null) {
                throw new IllegalStateException("operational_retention_transaction_returned_null");
            }
            deletedOutbox.increment(result.outbox());
            deletedInbox.increment(result.inbox());
            deletedJobs.increment(result.downloadJobs());
            deletedTokens.increment(result.tokens());
            deletedSessions.increment(result.sessions());
            deletedAudit.increment(result.audit());
            if (result.total() > 0) {
                LOGGER.info(
                        "Operational retention pruned {} rows (outbox={}, inbox={}, jobs={}, tokens={}, sessions={}, audit={})",
                        result.total(),
                        result.outbox(),
                        result.inbox(),
                        result.downloadJobs(), result.tokens(), result.sessions(), result.audit());
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
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp messageCutoff = Timestamp.from(clock.instant().minus(messageRetention));
        int outbox = jdbc.update(
                """
                DELETE FROM core_outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at <= ?
                ORDER BY published_at ASC, id ASC
                LIMIT ?
                """,
                now,
                BATCH_SIZE);
        // Un correo de acceso que ya no puede usarse no debe seguir reintentándose.
        outbox += jdbc.update("""
                DELETE FROM core_outbox_events
                WHERE published_at IS NULL AND event_type = 'notification.email.requested'
                  AND occurred_at <= ? AND claim_token IS NULL
                ORDER BY occurred_at ASC, id ASC LIMIT ?
                """, Timestamp.from(clock.instant().minus(magicLinkTtl)), BATCH_SIZE);
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
                  AND updated_at <= ?
                ORDER BY updated_at ASC, id ASC
                LIMIT ?
                """,
                now,
                BATCH_SIZE);
        int tokens = jdbc.update("""
                DELETE FROM identity_tokens
                WHERE consumed_at IS NOT NULL OR expires_at <= ?
                ORDER BY expires_at ASC, id ASC LIMIT ?
                """, now, BATCH_SIZE);
        int sessions = jdbc.update("""
                DELETE FROM SPRING_SESSION WHERE EXPIRY_TIME <= ?
                ORDER BY EXPIRY_TIME ASC, PRIMARY_ID ASC LIMIT ?
                """, clock.millis(), BATCH_SIZE);
        int audit = jdbc.update("""
                DELETE FROM admin_audit_logs WHERE created_at <= ?
                ORDER BY created_at ASC, id ASC LIMIT ?
                """, Timestamp.from(clock.instant().minus(auditRetention)), BATCH_SIZE);
        return new RetentionResult(outbox, inbox, jobs, tokens, sessions, audit);
    }

    /** Descuenta una pasada del plazo máximo y rechaza políticas sin margen o superiores a un día. */
    private static Duration effectiveRetention(Duration retention, Duration interval) {
        if (interval.isNegative() || interval.isZero() || retention.compareTo(interval) <= 0
                || retention.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("retention_must_exceed_interval_and_not_exceed_24h");
        }
        return retention.minus(interval);
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
     * @param tokens Enlaces consumidos o caducados eliminados.
     * @param sessions Sesiones caducadas eliminadas junto con sus atributos.
     * @param audit Entradas de auditoría fuera de plazo eliminadas.
     * @since 0.1.0
     * @version 0.1.0
     * @category Mensajería y retención
     */
    public record RetentionResult(int outbox, int inbox, int downloadJobs, int tokens, int sessions, int audit) {
        /**
         * Suma las eliminaciones de las categorías detectando desbordamiento entero.
         *
         * @return total de filas eliminadas.
         * @throws ArithmeticException si la suma no cabe en un entero.
         */
        public int total() {
            return Math.addExact(Math.addExact(Math.addExact(outbox, inbox), downloadJobs),
                    Math.addExact(Math.addExact(tokens, sessions), audit));
        }
    }
}
