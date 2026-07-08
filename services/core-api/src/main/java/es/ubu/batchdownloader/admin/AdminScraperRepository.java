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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminScraperRepository {
    private static final Set<String> COMMANDS = Set.of("pause", "resume", "stop", "run_once");
    private final JdbcTemplate jdbc;

    public AdminScraperRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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

    public List<ScraperQueueState> queues() {
        List<ScraperQueueState> states = new ArrayList<>();
        for (String queue : List.of("searcher_filter", "filter_scraper")) {
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

    public List<ScraperMetricItem> metrics(int limit) {
        return jdbc.query(
                """
                SELECT available, review, unavailable, queued_searcher_filter, queued_filter_scraper, captured_at
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
                        rs.getTimestamp("captured_at").toLocalDateTime()),
                Math.max(1, Math.min(limit, 200))).reversed();
    }

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

    public ScraperEvent event() {
        return new ScraperEvent(
                "scraper.changed",
                scraperVersion(),
                queues(),
                metrics(60),
                snapshots(),
                LocalDateTime.now());
    }

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

    public int pruneTerminalQueueItems() {
        return jdbc.update(
                """
                DELETE FROM scraper_work_items
                WHERE status IN ('completed', 'discarded')
                """);
    }

    public void enqueueCommand(String command, String actor) {
        if (!COMMANDS.contains(command)) {
            throw new ConflictException("unsupported_scraper_command", "Comando de scraper no soportado.");
        }
        String status = "run_once".equals(command) ? "completed" : "pending";
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                """
                INSERT INTO scraper_commands
                (id, command, status, message, created_by, created_at, consumed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UuidBytes.fromUuid(UUID.randomUUID()),
                command,
                status,
                "run_once".equals(command) ? "Triggered through scraper internal API." : null,
                actor,
                now,
                "run_once".equals(command) ? now : null);
    }

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

    private ScraperRunSummary run(ResultSet rs) throws SQLException {
        return new ScraperRunSummary(
                UuidBytes.toUuid(rs.getBytes("id")).toString(),
                rs.getString("status"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("heartbeat_at").toLocalDateTime(),
                nullableDate(rs, "finished_at"),
                rs.getInt("apps_discovered"),
                rs.getInt("apps_resolved"),
                rs.getInt("apps_failed"),
                rs.getInt("apps_skipped"),
                rs.getString("current_package_id"),
                rs.getString("current_app_name"),
                rs.getString("current_phase"),
                rs.getBoolean("stop_requested"),
                nullableDate(rs, "paused_at"),
                rs.getString("error_summary"));
    }

    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
