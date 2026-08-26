package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.AdminAuditItem;
import es.ubu.batchdownloader.admin.AdminDtos.ResolverLogItem;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperEvent;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperMetricItem;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperQueueItem;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperQueueState;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperRunSummary;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperSnapshotItem;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Gestiona la persistencia y consulta de {@code AdminScraperRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class AdminScraperRepository {
    /**
     * Constante que define {@code COMMANDS}.
     */
    private static final Set<String> COMMANDS = Set.of("pause", "resume", "stop", "force_stop", "run_once");
    /** Ventana de conservación de los elementos terminales de cola. */
    private static final Duration TERMINAL_RETENTION = Duration.ofDays(30);
    /** Límite por ejecución para no monopolizar la base de datos compartida. */
    private static final int RETENTION_BATCH_SIZE = 500;
    /**
     * Estado {@code jdbc} mantenido por {@code AdminScraperRepository}.
     */
    private final JdbcTemplate jdbc;
    /** Reloj inyectado para hacer determinista el límite de retención. */
    private final Clock clock;

    /**
     * Inicializa una instancia de {@code AdminScraperRepository}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @param clock Reloj UTC utilizado para calcular la antigüedad.
     */
    public AdminScraperRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Ejecuta la operación {@code runs}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<ScraperRunSummary> runs(int limit) {
        return jdbc.query(
                """
                SELECT * FROM scrape_runs
                ORDER BY started_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> run(rs),
                Math.max(1, Math.min(limit, 100)));
    }

    /**
     * Ejecuta la operación {@code current}.
     *
     * @return Resultado producido por {@code current}.
     */
    public ScraperRunSummary current() {
        List<ScraperRunSummary> runs = jdbc.query(
                """
                SELECT * FROM scrape_runs
                ORDER BY (status = 'running') DESC, started_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> run(rs));
        return runs.isEmpty() ? null : runs.get(0);
    }

    /**
     * Ejecuta la operación {@code logs}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<ResolverLogItem> logs(int limit) {
        return jdbc.query(
                """
                SELECT id, phase, status, message, safe_metadata, created_at
                FROM resolver_logs
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ResolverLogItem(
                        UuidBytes.toUuid(rs.getBytes("id")).toString(),
                        rs.getString("phase"),
                        rs.getString("status"),
                        rs.getString("message"),
                        rs.getString("safe_metadata"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                Math.max(1, Math.min(limit, 500)));
    }

    /**
     * Ejecuta la operación {@code queues}.
     *
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<ScraperQueueState> queues() {
        List<ScraperQueueState> states = new ArrayList<>();
        for (String queue : List.of(
                "searcher_filter", "filter_scraper", "scraper_so_filter", "so_filter_descriptor")) {
            Map<String, Long> counts = new LinkedHashMap<>();
            jdbc.queryForList(
                    """
                    SELECT status, COUNT(*) AS item_count
                    FROM scraper_work_items
                    WHERE queue = ?
                    GROUP BY status
                    """,
                    queue)
                    .forEach(row -> counts.put(
                            (String) row.get("status"),
                            ((Number) row.get("item_count")).longValue()));
            List<ScraperQueueItem> items = jdbc.query(
                    """
                    SELECT id, package_id, app_name, status, attempts, updated_at
                    FROM scraper_work_items
                    WHERE queue = ? AND status IN ('queued', 'in_progress')
                    ORDER BY (status = 'in_progress') DESC, updated_at DESC
                    LIMIT 30
                    """,
                    (rs, rowNum) -> new ScraperQueueItem(
                            UuidBytes.toUuid(rs.getBytes("id")).toString(),
                            rs.getString("package_id"),
                            rs.getString("app_name"),
                            rs.getString("status"),
                            rs.getInt("attempts"),
                            rs.getTimestamp("updated_at").toLocalDateTime()),
                    queue);
            states.add(new ScraperQueueState(
                    queue,
                    counts.getOrDefault("queued", 0L),
                    counts.getOrDefault("in_progress", 0L),
                    counts.getOrDefault("completed", 0L),
                    counts.getOrDefault("discarded", 0L),
                    counts.getOrDefault("failed", 0L),
                    items));
        }
        return states;
    }

    /**
     * Ejecuta la operación {@code metrics}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<ScraperMetricItem> metrics(int limit) {
        return jdbc.query(
                """
                SELECT available, review, unavailable, queued_searcher_filter,
                       queued_filter_scraper, queued_scraper_so_filter,
                       queued_so_filter_descriptor, captured_at
                FROM scraper_metric_snapshots
                ORDER BY captured_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new ScraperMetricItem(
                        rs.getInt("available"),
                        rs.getInt("review"),
                        rs.getInt("unavailable"),
                        rs.getInt("queued_searcher_filter"),
                        rs.getInt("queued_filter_scraper"),
                        rs.getInt("queued_scraper_so_filter"),
                        rs.getInt("queued_so_filter_descriptor"),
                        rs.getTimestamp("captured_at").toLocalDateTime()),
                Math.max(1, Math.min(limit, 200))).reversed();
    }

    /**
     * Ejecuta la operación {@code snapshots}.
     *
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<ScraperSnapshotItem> snapshots() {
        Map<String, ScraperSnapshotItem> byStage = new LinkedHashMap<>();
        List<ScraperSnapshotItem> snapshots = jdbc.query(
                """
                SELECT stage, package_id, app_name, url, html, captured_at
                FROM scraper_worker_snapshots
                WHERE expires_at >= NOW()
                ORDER BY captured_at DESC
                LIMIT 30
                """,
                (rs, rowNum) -> new ScraperSnapshotItem(
                        rs.getString("stage"),
                        rs.getString("package_id"),
                        rs.getString("app_name"),
                        rs.getString("url"),
                        rs.getString("html"),
                        rs.getTimestamp("captured_at").toLocalDateTime()));
        snapshots.forEach(snapshot -> byStage.putIfAbsent(snapshot.stage(), snapshot));
        return List.copyOf(byStage.values());
    }

    /**
     * Ejecuta la operación {@code event}.
     *
     * @return Resultado producido por {@code event}.
     */
    public ScraperEvent event() {
        return new ScraperEvent(
                "scraper.changed",
                scraperVersion(),
                queues(),
                metrics(60),
                snapshots(),
                LocalDateTime.now());
    }

    /**
     * Ejecuta la operación {@code scraperVersion}.
     *
     * @return Resultado producido por {@code scraperVersion}.
     */
    public String scraperVersion() {
        String token = jdbc.queryForObject(
                """
                SELECT CONCAT(
                    COALESCE((SELECT UNIX_TIMESTAMP(MAX(updated_at)) FROM scraper_work_items), 0), ':',
                    COALESCE((SELECT UNIX_TIMESTAMP(MAX(captured_at)) FROM scraper_metric_snapshots), 0), ':',
                    COALESCE((SELECT UNIX_TIMESTAMP(MAX(captured_at)) FROM scraper_worker_snapshots), 0), ':',
                    COALESCE((SELECT UNIX_TIMESTAMP(MAX(heartbeat_at)) FROM scrape_runs), 0)
                )
                """,
                String.class);
        return Integer.toHexString((token == null ? "" : token).hashCode());
    }

    /**
     * Recupera los elementos afectados mediante {@code recoverStuckQueueItems}.
     *
     * @return Número de elementos afectados por la operación.
     */
    public int recoverStuckQueueItems() {
        return jdbc.update(
                """
                UPDATE scraper_work_items
                SET status = 'queued',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    available_at = NOW(),
                    last_error = NULL,
                    updated_at = NOW()
                WHERE status = 'in_progress'
                  AND (lease_expires_at IS NULL OR lease_expires_at < NOW())
                """);
    }

    /**
     * Libera el recurso solicitado mediante {@code releaseInProgressQueueItems}.
     *
     * @return Resultado producido por {@code releaseInProgressQueueItems}.
     */
    public int releaseInProgressQueueItems() {
        return jdbc.update(
                """
                UPDATE scraper_work_items
                SET status = 'queued',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    available_at = NOW(),
                    last_error = NULL,
                    updated_at = NOW()
                WHERE status = 'in_progress'
                """);
    }

    /**
     * Reintenta los elementos afectados mediante {@code retryFailedQueueItems}.
     *
     * @return Resultado producido por {@code retryFailedQueueItems}.
     */
    public int retryFailedQueueItems() {
        return jdbc.update(
                """
                UPDATE scraper_work_items
                SET status = 'queued',
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    available_at = NOW(),
                    last_error = NULL,
                    updated_at = NOW()
                WHERE status = 'failed'
                """);
    }

    /**
     * Ejecuta la operación {@code pruneTerminalQueueItems}.
     *
     * @return Resultado producido por {@code pruneTerminalQueueItems}.
     */
    public int pruneTerminalQueueItems() {
        return jdbc.update(
                """
                DELETE FROM scraper_work_items
                WHERE status IN ('completed', 'discarded')
                  AND updated_at < ?
                  AND lease_owner IS NULL
                  AND lease_expires_at IS NULL
                ORDER BY updated_at ASC, id ASC
                LIMIT ?
                """,
                Timestamp.from(clock.instant().minus(TERMINAL_RETENTION)),
                RETENTION_BATCH_SIZE);
    }

    /**
     * Ejecuta la operación {@code forceStopRunningRuns}.
     *
     * @return Resultado producido por {@code forceStopRunningRuns}.
     */
    public int forceStopRunningRuns() {
        return jdbc.update(
                """
                UPDATE scrape_runs
                SET status = 'partial',
                    finished_at = NOW(),
                    heartbeat_at = NOW(),
                    current_phase = 'force_stopped',
                    stop_requested = TRUE,
                    paused_at = NULL,
                    error_summary = 'Force stopped by admin.'
                WHERE status = 'running'
                """);
    }

    /**
     * Encola la operación solicitada mediante {@code enqueueCommand}.
     *
     * @param command Comando que debe procesarse.
     * @param actor Identidad del actor que solicita la operación.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public void enqueueCommand(String command, String actor) {
        if (!COMMANDS.contains(command)) {
            throw new ConflictException("unsupported_scraper_command", "Comando de scraper no soportado.");
        }
        if ("run_once".equals(command)) {
            enqueueRun("incremental", List.of(), actor);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                """
                INSERT INTO scraper_commands
                (id, command, status, message, created_by, created_at, consumed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UuidBytes.fromUuid(UUID.randomUUID()),
                command,
                "pending",
                null,
                actor,
                now,
                null);
    }

    /**
     * Persiste una solicitud de scraping hasta que el scheduler la reclame.
     *
     * @param scope Alcance validado por el controlador.
     * @param appIds UUID concretos para {@code selected}.
     * @param actor Identidad autenticada.
     * @return Identificador durable de la solicitud.
     */
    public UUID enqueueRun(String scope, List<UUID> appIds, String actor) {
        UUID requestId = UUID.randomUUID();
        String appIdsJson = appIds == null || appIds.isEmpty()
                ? null
                : appIds.stream()
                        .map(id -> "\"" + id + "\"")
                        .collect(Collectors.joining(",", "[", "]"));
        jdbc.update(
                """
                INSERT INTO scraper_commands
                (id, command, scope, app_ids_json, status, message, created_by,
                 created_at, consumed_at, started_at, run_id)
                VALUES (?, 'run_once', ?, ?, 'pending', NULL, ?, ?, NULL, NULL, NULL)
                """,
                UuidBytes.fromUuid(requestId),
                scope,
                appIdsJson,
                actor,
                LocalDateTime.now());
        return requestId;
    }

    /**
     * Ejecuta la operación {@code audit}.
     *
     * @param limit Número máximo de elementos que se recuperarán.
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<AdminAuditItem> audit(int limit) {
        return jdbc.query(
                """
                SELECT actor, action, target_type, target_id, safe_metadata, created_at
                FROM admin_audit_logs
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new AdminAuditItem(
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("target_type"),
                        rs.getString("target_id"),
                        rs.getString("safe_metadata"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                Math.max(1, Math.min(limit, 200)));
    }

    /**
     * Ejecuta la operación {@code run}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @return Resultado producido por {@code run}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private ScraperRunSummary run(ResultSet rs) throws SQLException {
        return new ScraperRunSummary(
                UuidBytes.toUuid(rs.getBytes("id")).toString(),
                rs.getString("status"),
                rs.getString("scope"),
                nullableUuid(rs, "request_id"),
                rs.getInt("target_count"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("heartbeat_at").toLocalDateTime(),
                nullableDate(rs, "finished_at"),
                rs.getInt("apps_discovered"),
                rs.getInt("apps_resolved"),
                rs.getInt("apps_failed"),
                rs.getInt("apps_skipped"),
                rs.getInt("apps_confirmed_missing"),
                rs.getInt("apps_needs_review"),
                rs.getInt("apps_transient_failed"),
                rs.getInt("apps_skipped_unchanged"),
                rs.getString("current_package_id"),
                rs.getString("current_app_name"),
                rs.getString("current_phase"),
                rs.getBoolean("stop_requested"),
                nullableDate(rs, "paused_at"),
                rs.getString("error_summary"));
    }

    /** Devuelve un UUID binario opcional como texto. */
    private String nullableUuid(ResultSet rs, String column) throws SQLException {
        byte[] value = rs.getBytes(column);
        return value == null ? null : UuidBytes.toUuid(value).toString();
    }

    /**
     * Ejecuta la operación {@code nullableDate}.
     *
     * @param rs Valor de {@code rs} utilizado por la operación.
     * @param column Valor de {@code column} utilizado por la operación.
     * @return Resultado producido por {@code nullableDate}.
     * @throws SQLException Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
