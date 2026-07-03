package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogChangeEvent;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.DownloadOption;
import es.ubu.batchdownloader.catalog.CatalogDtos.LastScrapeRun;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AppListItem> search(
            String query,
            String status,
            String operatingSystem,
            String architecture,
            List<String> tags,
            String tagMode,
            String sort,
            int page,
            int pageSize) {
        String orderBy = "updated".equals(sort)
                ? "a.updated_at DESC, a.id ASC"
                : "a.normalized_name ASC, a.id ASC";
        StringBuilder sql = new StringBuilder("""
                SELECT a.*
                FROM software_apps a
                JOIN (
                    SELECT a.id
                    FROM software_apps a
                    WHERE a.app_status = 'active'
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, query, status, operatingSystem, architecture, tags, tagMode);
        sql.append(" ORDER BY ").append(orderBy);
        sql.append(" LIMIT ? OFFSET ?) page ON page.id = a.id ORDER BY ").append(orderBy);
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        return jdbc.query(sql.toString(), this::mapListItem, params.toArray());
    }

    public long count(
            String query,
            String status,
            String operatingSystem,
            String architecture,
            List<String> tags,
            String tagMode) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM software_apps a
                WHERE a.app_status = 'active'
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, query, status, operatingSystem, architecture, tags, tagMode);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    public AppDetails details(String publicId) {
        UUID id = softwareAppId(publicId);
        List<AppDetails> matches = jdbc.query(
                """
                SELECT a.*
                FROM software_apps a
                WHERE a.app_status = 'active' AND a.id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> mapDetails(rs),
                UuidBytes.fromUuid(id));
        if (matches.isEmpty()) {
            throw new NotFoundException("app_not_found", "La aplicacion no existe.");
        }
        return matches.get(0);
    }

    public UUID softwareAppId(String publicId) {
        UUID parsed = parseUuid(publicId);
        List<UUID> ids = jdbc.query(
                """
                SELECT id FROM software_apps
                WHERE (? IS NOT NULL AND id = ?) OR slug = ? OR winstall_id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                parsed == null ? null : UuidBytes.fromUuid(parsed),
                parsed == null ? null : UuidBytes.fromUuid(parsed),
                publicId,
                publicId);
        if (ids.isEmpty()) {
            throw new NotFoundException("app_not_found", "La aplicacion no existe.");
        }
        return ids.get(0);
    }

    public CatalogStatsResponse stats() {
        long total = scalarLong("SELECT COUNT(*) FROM software_apps WHERE app_status = 'active'");
        Map<String, Long> filters = new LinkedHashMap<>();
        filters.put("all", total);
        filters.put("available", scalarLong("""
                SELECT COUNT(DISTINCT software_app_id) FROM download_sources
                WHERE resolution_status IN ('direct', 'fallback')
                """));
        filters.put("review", scalarLong("""
                SELECT COUNT(DISTINCT software_app_id) FROM download_sources
                WHERE resolution_status = 'requires_manual_review'
                """));
        filters.put("missing", scalarLong("""
                SELECT COUNT(DISTINCT software_app_id) FROM download_sources
                WHERE resolution_status IN ('missing', 'broken')
                """));
        LastScrapeRun last = latestRun();
        return new CatalogStatsResponse(total, filters, last, LocalDateTime.now());
    }

    public CatalogChangeEvent changeEvent() {
        return new CatalogChangeEvent("catalog.changed", changeVersion(), LocalDateTime.now());
    }

    public String changeVersion() {
        String appToken = jdbc.queryForObject(
                """
                SELECT CONCAT(COUNT(*), ':', COALESCE(UNIX_TIMESTAMP(MAX(updated_at)), 0))
                FROM software_apps
                WHERE app_status = 'active'
                """,
                String.class);
        List<String> runTokens = jdbc.query(
                """
                SELECT CONCAT(
                    HEX(id), ':', status, ':',
                    COALESCE(UNIX_TIMESTAMP(heartbeat_at), 0), ':',
                    apps_discovered, ':', apps_resolved, ':', apps_failed, ':',
                    COALESCE(apps_skipped, 0), ':',
                    COALESCE(current_package_id, ''), ':',
                    COALESCE(current_phase, '')
                ) AS token
                FROM scrape_runs
                ORDER BY started_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString("token"));
        return Integer.toHexString(((appToken == null ? "" : appToken) + "|" + (runTokens.isEmpty() ? "" : runTokens.get(0))).hashCode());
    }

    private void appendFilters(
            StringBuilder sql,
            List<Object> params,
            String query,
            String status,
            String operatingSystem,
            String architecture,
            List<String> tags,
            String tagMode) {
        if (query != null && !query.isBlank()) {
            String like = "%" + query.toLowerCase(Locale.ROOT).trim() + "%";
            sql.append("""
                    AND (
                        LOWER(a.name) LIKE ? OR LOWER(a.publisher) LIKE ? OR
                        LOWER(a.description) LIKE ? OR LOWER(a.long_description) LIKE ? OR
                        LOWER(a.winstall_id) LIKE ? OR
                        EXISTS (
                            SELECT 1 FROM software_app_tags sat
                            WHERE sat.software_app_id = a.id AND sat.normalized_tag LIKE ?
                        )
                    )
                    """);
            for (int i = 0; i < 6; i++) {
                params.add(like);
            }
        }
        appendSourceFilter(sql, params, status, operatingSystem, architecture);
        List<String> normalizedTags = tags.stream()
                .filter(tag -> tag != null && !tag.isBlank())
                .map(tag -> tag.toLowerCase(Locale.ROOT).trim())
                .toList();
        if (!normalizedTags.isEmpty()) {
            if ("any".equals(tagMode)) {
                sql.append(" AND EXISTS (SELECT 1 FROM software_app_tags t WHERE t.software_app_id = a.id AND t.normalized_tag IN (");
                appendPlaceholders(sql, normalizedTags.size());
                sql.append("))");
                params.addAll(normalizedTags);
            } else {
                for (String tag : normalizedTags) {
                    sql.append(" AND EXISTS (SELECT 1 FROM software_app_tags t WHERE t.software_app_id = a.id AND t.normalized_tag = ?)");
                    params.add(tag);
                }
            }
        }
    }

    private void appendSourceFilter(
            StringBuilder sql,
            List<Object> params,
            String status,
            String operatingSystem,
            String architecture) {
        boolean hasStatus = status != null && !status.isBlank() && !"all".equals(status);
        boolean hasOperatingSystem = operatingSystem != null && !operatingSystem.isBlank();
        boolean hasArchitecture = architecture != null && !architecture.isBlank();
        if (!hasStatus && !hasOperatingSystem && !hasArchitecture) {
            return;
        }

        sql.append("""
                AND EXISTS (
                    SELECT 1 FROM download_sources ds
                    WHERE ds.software_app_id = a.id
                """);
        if (hasStatus) {
            if ("available".equals(status)) {
                sql.append(" AND ds.resolution_status IN ('direct', 'fallback')");
            } else if ("review".equals(status)) {
                sql.append(" AND ds.resolution_status = 'requires_manual_review'");
            } else if ("missing".equals(status)) {
                sql.append(" AND ds.resolution_status IN ('missing', 'broken')");
            } else {
                sql.append(" AND ds.resolution_status = ?");
                params.add(status);
            }
        }
        if (hasOperatingSystem) {
            sql.append(" AND ds.operating_system = ?");
            params.add(operatingSystem);
        }
        if (hasArchitecture) {
            sql.append(" AND ds.architecture = ?");
            params.add(architecture);
        }
        sql.append(")");
    }

    private AppListItem mapListItem(ResultSet rs, int rowNum) throws SQLException {
        AppBasics app = readBasics(rs);
        SourceSnapshot source = sourceFor(app.dbId());
        return new AppListItem(
                app.dbId().toString(),
                app.slug(),
                app.winstallId(),
                app.name(),
                app.publisher(),
                app.description(),
                app.longDescription(),
                tagsFor(app.dbId()),
                app.iconUrl(),
                app.latestVersion(),
                source.sourceLabel(),
                source.resolutionStatus(),
                source.validationStatus(),
                source.downloadable(),
                app.updatedAt());
    }

    private AppDetails mapDetails(ResultSet rs) throws SQLException {
        AppBasics app = readBasics(rs);
        SourceSnapshot source = sourceFor(app.dbId());
        List<DownloadOption> options = downloadOptions(app.dbId());
        return new AppDetails(
                app.dbId().toString(),
                app.slug(),
                app.winstallId(),
                app.name(),
                app.publisher(),
                app.description(),
                app.longDescription(),
                tagsFor(app.dbId()),
                app.iconUrl(),
                app.officialUrl(),
                source.originUrl() != null ? source.originUrl() : app.officialUrl(),
                app.latestVersion(),
                source.filename(),
                source.extension() == null ? null : source.extension().replace(".", "").toUpperCase(Locale.ROOT),
                source.contentType(),
                source.sizeBytes(),
                source.finalDomain(),
                source.score(),
                source.resolutionStatus(),
                source.validationStatus(),
                source.sourceLabel(),
                source.checkedAt(),
                source.expiresAt(),
                options,
                notesFor(source));
    }

    private AppBasics readBasics(ResultSet rs) throws SQLException {
        return new AppBasics(
                UuidBytes.toUuid(rs.getBytes("id")),
                rs.getString("winstall_id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("publisher"),
                rs.getString("description"),
                rs.getString("long_description"),
                rs.getString("icon_url"),
                rs.getString("official_url"),
                rs.getString("latest_version"),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private SourceSnapshot sourceFor(UUID appId) {
        List<SourceSnapshot> snapshots = jdbc.query(
                """
                SELECT ds.id AS source_id, ds.initial_url, ds.resolution_status, ds.validation_status,
                       rs.id AS resolved_id, rs.filename, rs.extension, rs.content_type, rs.size_bytes,
                       rs.final_domain, rs.score, rs.checked_at, rs.expires_at, rs.metadata_json
                FROM download_sources ds
                LEFT JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    AND rs.validation_status = 'valid'
                WHERE ds.software_app_id = ?
                ORDER BY (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC, rs.checked_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> {
                    String resolution = rs.getString("resolution_status");
                    String validation = rs.getString("validation_status");
                    boolean downloadable = rs.getBytes("resolved_id") != null
                            && "valid".equals(rs.getString("validation_status"))
                            && rs.getTimestamp("expires_at") != null
                            && rs.getTimestamp("expires_at").toLocalDateTime().isAfter(LocalDateTime.now());
                    return new SourceSnapshot(
                            resolution == null ? "missing" : resolution,
                            validation == null ? "unchecked" : validation,
                            sourceLabel(resolution),
                            rs.getString("initial_url"),
                            rs.getString("filename"),
                            rs.getString("extension"),
                            rs.getString("content_type"),
                            nullableLong(rs, "size_bytes"),
                            rs.getString("final_domain"),
                            nullableInt(rs, "score"),
                            nullableDate(rs, "checked_at"),
                            nullableDate(rs, "expires_at"),
                            downloadable);
                },
                UuidBytes.fromUuid(appId));
        return snapshots.isEmpty()
                ? SourceSnapshot.empty()
                : snapshots.get(0);
    }

    private List<DownloadOption> downloadOptions(UUID appId) {
        return jdbc.query(
                """
                SELECT rs.id, rs.filename, rs.extension, rs.final_domain, rs.score, rs.status, rs.metadata_json
                FROM download_sources ds
                JOIN resolved_sources rs ON rs.download_source_id = ds.id
                WHERE ds.software_app_id = ? AND rs.validation_status = 'valid'
                ORDER BY (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC, rs.checked_at DESC
                LIMIT 5
                """,
                (rs, rowNum) -> new DownloadOption(
                        UuidBytes.toUuid(rs.getBytes("id")).toString(),
                        rs.getString("filename"),
                        rs.getString("extension"),
                        sourceLabel(rs.getString("status")),
                        rs.getInt("score"),
                        rs.getString("final_domain"),
                        rowNum == 0),
                UuidBytes.fromUuid(appId));
    }

    private List<String> tagsFor(UUID appId) {
        return jdbc.queryForList(
                "SELECT tag FROM software_app_tags WHERE software_app_id = ? ORDER BY tag",
                String.class,
                UuidBytes.fromUuid(appId));
    }

    private LastScrapeRun latestRun() {
        List<LastScrapeRun> runs = jdbc.query(
                """
                SELECT * FROM scrape_runs ORDER BY started_at DESC LIMIT 1
                """,
                (rs, rowNum) -> new LastScrapeRun(
                        rs.getString("status"),
                        rs.getTimestamp("started_at").toLocalDateTime(),
                        rs.getTimestamp("heartbeat_at").toLocalDateTime(),
                        nullableDate(rs, "finished_at"),
                        rs.getInt("apps_discovered"),
                        rs.getInt("apps_resolved"),
                        rs.getInt("apps_failed"),
                        hasColumn(rs, "apps_skipped") ? rs.getInt("apps_skipped") : 0,
                        hasColumn(rs, "current_package_id") ? rs.getString("current_package_id") : null,
                        hasColumn(rs, "current_app_name") ? rs.getString("current_app_name") : null,
                        hasColumn(rs, "current_phase") ? rs.getString("current_phase") : null));
        return runs.isEmpty() ? null : runs.get(0);
    }

    private long scalarLong(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private void appendPlaceholders(StringBuilder sql, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
    }

    private String sourceLabel(String status) {
        if ("direct".equals(status)) {
            return "Sitio oficial";
        }
        if ("fallback".equals(status)) {
            return "Fallback Winstall";
        }
        return "No disponible";
    }

    private String notesFor(SourceSnapshot source) {
        if ("direct".equals(source.resolutionStatus())) {
            return "Instalador obtenido desde la fuente oficial validada.";
        }
        if ("fallback".equals(source.resolutionStatus())) {
            return "Instalador obtenido desde el fallback de Winstall.";
        }
        return "El instalador necesita revision o no esta disponible.";
    }

    private LocalDateTime nullableDate(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private boolean hasColumn(ResultSet rs, String column) {
        try {
            rs.findColumn(column);
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private UUID parseUuid(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public record AppBasics(
            UUID dbId,
            String winstallId,
            String slug,
            String name,
            String publisher,
            String description,
            String longDescription,
            String iconUrl,
            String officialUrl,
            String latestVersion,
            LocalDateTime updatedAt) {}

    private record SourceSnapshot(
            String resolutionStatus,
            String validationStatus,
            String sourceLabel,
            String originUrl,
            String filename,
            String extension,
            String contentType,
            Long sizeBytes,
            String finalDomain,
            Integer score,
            LocalDateTime checkedAt,
            LocalDateTime expiresAt,
            boolean downloadable) {
        static SourceSnapshot empty() {
            return new SourceSnapshot(
                    "missing", "unchecked", "No disponible", null, null, null, null,
                    null, null, null, null, null, false);
        }
    }
}
