package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.AdminAuditItem;
import es.ubu.batchdownloader.admin.AdminDtos.ResolverLogItem;
import es.ubu.batchdownloader.admin.AdminDtos.ScraperRunSummary;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
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
