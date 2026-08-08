package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.common.UuidBytes;
import es.ubu.batchdownloader.identity.api.AccountDtos.DashboardCounts;
import es.ubu.batchdownloader.identity.api.AccountDtos.DownloadHistoryItem;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Consultas de lectura del área de cuenta. */
@Repository
public class AccountOverviewRepository {
    private final JdbcTemplate jdbc;

    public AccountOverviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<DownloadHistoryItem> downloads(UUID userId, int page, int pageSize) {
        return jdbc.query(
                """
                SELECT history.app_id, history.app_name, history.job_id, history.downloaded_at,
                       app.slug, app.icon_url
                FROM user_download_history history
                LEFT JOIN software_apps app ON app.id = history.app_id
                WHERE history.user_id = ?
                ORDER BY history.downloaded_at DESC, history.id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, rowNum) -> new DownloadHistoryItem(
                        UuidBytes.toUuid(rs.getBytes("app_id")).toString(),
                        rs.getString("app_name"),
                        rs.getString("slug"),
                        rs.getString("icon_url"),
                        rs.getString("job_id"),
                        rs.getTimestamp("downloaded_at").toLocalDateTime()),
                userId.toString(), pageSize, (page - 1) * pageSize);
    }

    public long downloadCount(UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_download_history WHERE user_id = ?",
                Long.class, userId.toString());
        return count == null ? 0 : count;
    }

    public DashboardCounts counts(UUID userId) {
        BundleCounts bundles = jdbc.queryForObject(
                """
                SELECT COUNT(*) AS total,
                       COALESCE(SUM(visibility = 'public'), 0) AS public_total,
                       COALESCE(SUM(visibility = 'private'), 0) AS private_total
                FROM bundles
                WHERE owner_id = ? AND type = 'user'
                """,
                (rs, rowNum) -> new BundleCounts(
                        rs.getLong("total"), rs.getLong("public_total"),
                        rs.getLong("private_total")),
                userId.toString());
        return new DashboardCounts(
                bundles == null ? 0 : bundles.total(),
                bundles == null ? 0 : bundles.publicTotal(),
                bundles == null ? 0 : bundles.privateTotal(),
                downloadCount(userId));
    }

    private record BundleCounts(long total, long publicTotal, long privateTotal) {}
}
