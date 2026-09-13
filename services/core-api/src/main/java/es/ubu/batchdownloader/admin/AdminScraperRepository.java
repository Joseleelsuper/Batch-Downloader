package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminAuditDtos.AdminAuditItem;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ResolverLogItem;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperEvent;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperMetricItem;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperQueueItem;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperQueueState;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperRunSummary;
import es.ubu.batchdownloader.admin.ScraperOperationsDtos.ScraperSnapshotItem;
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
 * Lee la actividad persistente del scraper y registra comandos y recuperaciones administrativas
 * sobre sus propias colas, sin ejecutar los workers desde Core.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminScraperController
 * @see es.ubu.batchdownloader.admin.AdminScraperNotifier
 * @since 0.1.0
 * @version 0.1.0
 * @category Operaciones administrativas
 */
@Repository
public class AdminScraperRepository {
    /**
     * Valor compartido que fija c o m m a n d s para el comportamiento del componente.
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
     * Conecta las consultas operativas con el reloj utilizado por la retención de trabajos
     * terminales.
     *
     * @param jdbc Acceso JDBC a las tablas operativas y evidencias del catálogo.
     * @param clock Reloj que fecha evidencias y determina el corte de retención.
     */
    public AdminScraperRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Consulta ejecuciones desde la más reciente con un límite efectivo entre uno y cien.
     *
     * @param limit Máximo solicitado; se acota al intervalo documentado por cada consulta.
     * @return ejecuciones ordenadas por fecha de inicio descendente.
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
     * Prioriza una ejecución running y, si no hay ninguna, devuelve la última ejecución conocida
     * aunque haya terminado.
     *
     * @return ejecución seleccionada o null si todavía no hay historial.
     */
    public ScraperRunSummary current() {
        List<ScraperRunSummary> running = jdbc.query(
                """
                SELECT * FROM scrape_runs
                WHERE status = 'running'
                ORDER BY started_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> run(rs));
        if (!running.isEmpty()) {
            return running.get(0);
        }

        List<ScraperRunSummary> runs = jdbc.query(
                """
                SELECT * FROM scrape_runs
                ORDER BY started_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> run(rs));
        return runs.isEmpty() ? null : runs.get(0);
    }

    /**
     * Consulta los últimos diagnósticos de resolución con sus metadatos seguros y un límite entre
     * uno y quinientos.
     *
     * @param limit Máximo solicitado; se acota al intervalo documentado por cada consulta.
     * @return registros por fecha descendente.
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
     * Cuenta todos los estados de las cuatro colas y añade hasta treinta trabajos pendientes por
     * cola, priorizando los que ya están en curso.
     *
     * @return colas en el orden del pipeline con contadores y una muestra de actividad.
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
     * Selecciona entre una y doscientas capturas recientes y las invierte para representar la
     * evolución cronológica.
     *
     * @param limit Máximo solicitado; se acota al intervalo documentado por cada consulta.
     * @return capturas de métricas ordenadas desde la más antigua de la selección.
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
     * Busca las treinta capturas todavía vigentes más recientes y conserva la primera de cada
     * etapa.
     *
     * @return como máximo una captura por etapa, según el orden de recencia.
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
     * Agrupa versión, colas, sesenta capturas de métricas y snapshots vigentes en el evento
     * scraper.changed.
     *
     * @return estado administrativo listo para serializar y enviar por WebSocket.
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
     * Combina las últimas fechas de trabajos, métricas, snapshots y latidos en una huella corta
     * para detectar cambios de estado.
     *
     * @return hash hexadecimal opaco; no es una versión de software ni una huella criptográfica.
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
     * Reencola trabajos en curso cuyo arrendamiento venció o está ausente, limpiando propietario,
     * expiración y último error.
     *
     * @return cantidad de trabajos recuperados.
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
     * Reencola todos los trabajos en curso, incluso con arrendamiento vigente, y limpia la reserva
     * para una recuperación administrativa forzada.
     *
     * @return cantidad de trabajos liberados.
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
     * Vuelve a poner en cola los trabajos fallidos y elimina reserva y último error, conservando su
     * contador de intentos.
     *
     * @return cantidad de trabajos preparados para reintento.
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
     * Elimina un lote de trabajos completed o discarded más antiguos que TERMINAL_RETENTION y sin
     * reserva, empezando por los más antiguos.
     *
     * @return cantidad eliminada, acotada por RETENTION_BATCH_SIZE.
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
     * Marca las ejecuciones running como parciales y detenidas, solicita parada cooperativa y
     * elimina su pausa; no interrumpe procesos del scraper.
     *
     * @return cantidad de ejecuciones marcadas.
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
     * Registra un comando pendiente para que lo consuma el scraper; run_once se traduce a una
     * solicitud incremental sin selección explícita.
     *
     * @param command Comando permitido por COMMANDS; run_once equivale a una ejecución incremental.
     * @param actor UUID textual de la cuenta administrativa que solicitó la operación.
     * @throws es.ubu.batchdownloader.common.ConflictException si el comando no forma parte de la
     *     lista admitida.
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
     * Persiste una solicitud run_once con alcance y selección JSON opcional para que el scraper la
     * admita y asocie a su ejecución.
     *
     * @param scope Alcance de la ejecución validado por el controlador antes de encolarla.
     * @param appIds UUID de las aplicaciones objetivo; null o lista vacía se guarda como ausencia
     *     de selección.
     * @param actor UUID textual de la cuenta administrativa que solicitó la operación.
     * @return UUID de la solicitud pendiente, distinto del UUID de la futura ejecución.
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
     * Consulta las acciones administrativas más recientes con un límite efectivo entre uno y
     * doscientos.
     *
     * @param limit Máximo solicitado; se acota al intervalo documentado por cada consulta.
     * @return entradas de auditoría por fecha descendente.
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
     * Proyecta identidad, alcance, contadores, latido y estado de parada de una ejecución
     * persistida.
     *
     * @param rs Fila actual de la consulta JDBC.
     * @return resumen que mantiene null para fechas y solicitud ausentes.
     * @throws java.sql.SQLException si falla la lectura de una columna requerida.
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

    /**
     * Convierte un UUID binario opcional a su forma textual para la respuesta administrativa.
     *
     * @param rs Fila actual de la consulta JDBC.
     * @param column Nombre interno de la columna nullable que se desea leer.
     * @return UUID textual o null.
     * @throws java.sql.SQLException si no se puede leer la columna de identificador.
     */
    private String nullableUuid(ResultSet rs, String column) throws SQLException {
        byte[] value = rs.getBytes(column);
        return value == null ? null : UuidBytes.toUuid(value).toString();
    }

    /**
     * Lee una fecha JDBC opcional conservando la ausencia del dato.
     *
     * @param rs Fila actual de la consulta JDBC.
     * @param column Nombre interno de la columna nullable que se desea leer.
     * @return fecha local persistida o null.
     * @throws java.sql.SQLException si no se puede leer la columna de fecha.
     */
    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
