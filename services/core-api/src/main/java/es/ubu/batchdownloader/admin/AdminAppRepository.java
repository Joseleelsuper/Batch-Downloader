package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.PatchAppRequest;
import es.ubu.batchdownloader.admin.AdminDtos.PatchSourceRequest;
import es.ubu.batchdownloader.admin.AdminDtos.UpsertAppRequest;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.FernetUrlProtector;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AdminAppRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;
    private final FernetUrlProtector urlProtector;

    public AdminAppRepository(
            JdbcTemplate jdbc,
            CatalogRepository catalog,
            @Value("${app.url-protection-secret}") String urlProtectionSecret) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.urlProtector = new FernetUrlProtector(urlProtectionSecret);
    }

    @Transactional
    public AppDetails create(UpsertAppRequest request) {
        UUID id = UUID.randomUUID();
        String slug = normalizeSlug(isBlank(request.slug()) ? request.name() : request.slug());
        String winstallId = isBlank(request.winstallId()) ? "manual." + slug : request.winstallId().trim();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
                """
                INSERT INTO software_apps
                (id, winstall_id, slug, name, normalized_name, description, long_description,
                 long_description_status, publisher, icon_url, official_url, latest_version,
                 app_status, metadata_json, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, JSON_OBJECT('source', 'admin'), 0, ?, ?)
                """,
                UuidBytes.fromUuid(id),
                winstallId,
                slug,
                request.name().trim(),
                normalizeText(request.name()),
                request.description(),
                request.longDescription(),
                isBlank(request.longDescription()) ? "pending" : "completed",
                request.publisher(),
                request.iconUrl(),
                request.officialUrl(),
                request.latestVersion(),
                isBlank(request.appStatus()) ? "active" : request.appStatus(),
                now,
                now);
        replaceTags(id, request.tags(), "admin");
        createDefaultSource(id, request.officialUrl(), now);
        return catalog.details(id.toString());
    }

    @Transactional
    public AppDetails patch(String publicId, PatchAppRequest request) {
        UUID id = softwareAppId(publicId);
        AppDetails before = catalog.details(publicId);
        jdbc.update(
                """
                UPDATE software_apps
                SET name = ?, normalized_name = ?, publisher = ?, description = ?, long_description = ?,
                    long_description_status = ?, icon_url = ?, official_url = ?, latest_version = ?,
                    app_status = ?, updated_at = ?, version = version + 1
                WHERE id = ?
                """,
                coalesce(request.name(), before.name()),
                normalizeText(coalesce(request.name(), before.name())),
                coalesce(request.publisher(), before.publisher()),
                coalesce(request.description(), before.description()),
                coalesce(request.longDescription(), before.longDescription()),
                isBlank(coalesce(request.longDescription(), before.longDescription())) ? "pending" : "completed",
                coalesce(request.iconUrl(), before.iconUrl()),
                coalesce(request.officialUrl(), before.officialUrl()),
                coalesce(request.latestVersion(), before.latestVersion()),
                isBlank(request.appStatus()) ? "active" : request.appStatus(),
                LocalDateTime.now(),
                UuidBytes.fromUuid(id));
        return catalog.details(id.toString());
    }

    @Transactional
    public void replaceTags(String publicId, List<String> tags) {
        replaceTags(softwareAppId(publicId), tags, "admin");
    }

    @Transactional
    public void delete(String publicId) {
        assertScraperIdleForDeletion();
        UUID appId = softwareAppId(publicId);
        List<UUID> affectedBundles = jdbc.query(
                "SELECT bundle_id FROM bundle_items WHERE software_app_id = ?",
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("bundle_id")),
                UuidBytes.fromUuid(appId));
        deleteApps("WHERE id = ?", List.<Object>of(UuidBytes.fromUuid(appId)));
        refreshBundleCounts(affectedBundles);
    }

    @Transactional
    public int deleteAll() {
        assertScraperIdleForDeletion();
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM software_apps", Integer.class);
        jdbc.update("DELETE FROM scraper_worker_snapshots");
        jdbc.update("DELETE FROM scraper_metric_snapshots");
        jdbc.update("DELETE FROM scraper_work_items");
        deleteApps("", List.of());
        jdbc.update("UPDATE bundles SET app_count = 0, updated_at = ? WHERE app_count <> 0", LocalDateTime.now());
        return count == null ? 0 : count;
    }

    public AppCsvExport exportCsv() {
        List<ExportCandidate> candidates = jdbc.query(
                """
                SELECT HEX(a.id) AS app_key, a.name, a.winstall_id, a.official_url,
                       ds.operating_system, rs.extension, rs.resolved_url_encrypted
                FROM software_apps a
                LEFT JOIN download_sources ds ON ds.software_app_id = a.id
                LEFT JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    AND rs.validation_status = 'valid'
                WHERE a.app_status = 'active'
                ORDER BY a.normalized_name ASC,
                         a.id ASC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC,
                         rs.checked_at DESC
                """,
                this::mapExportCandidate);
        Map<String, ExportRow> rows = new LinkedHashMap<>();
        for (ExportCandidate candidate : candidates) {
            ExportRow row = rows.computeIfAbsent(candidate.appKey(), key -> new ExportRow(
                    candidate.name(),
                    winstallUrl(candidate.winstallId()),
                    blankToNone(candidate.officialUrl())));
            String platform = platformKey(candidate.operatingSystem(), candidate.extension());
            String url = urlProtector.reveal(candidate.encryptedUrl());
            if (platform != null && url != null && !url.isBlank()) {
                row.putIfMissing(platform, url);
            }
        }

        StringBuilder csv = new StringBuilder("Nombre,Winstall,URL,Windows,Linux,MacOS\r\n");
        for (ExportRow row : rows.values()) {
            csv.append(csvCell(row.name()))
                    .append(',')
                    .append(csvCell(row.winstall()))
                    .append(',')
                    .append(csvCell(row.officialUrl()))
                    .append(',')
                    .append(csvCell(row.windows()))
                    .append(',')
                    .append(csvCell(row.linux()))
                    .append(',')
                    .append(csvCell(row.macos()))
                    .append("\r\n");
        }
        return new AppCsvExport(csv.toString(), rows.size());
    }

    @Transactional
    public void patchSource(String sourceId, PatchSourceRequest request) {
        UUID id = parseUuid(sourceId);
        int updated = jdbc.update(
                """
                UPDATE download_sources
                SET operating_system = COALESCE(?, operating_system),
                    architecture = COALESCE(?, architecture),
                    initial_url = COALESCE(?, initial_url),
                    resolver_type = COALESCE(?, resolver_type),
                    resolution_status = COALESCE(?, resolution_status),
                    validation_status = COALESCE(?, validation_status),
                    updated_at = ?,
                    version = version + 1
                WHERE id = ?
                """,
                blankToNull(request.operatingSystem()),
                blankToNull(request.architecture()),
                blankToNull(request.initialUrl()),
                blankToNull(request.resolverType()),
                blankToNull(request.resolutionStatus()),
                blankToNull(request.validationStatus()),
                LocalDateTime.now(),
                UuidBytes.fromUuid(id));
        if (updated == 0) {
            throw new NotFoundException("source_not_found", "La fuente no existe.");
        }
    }

    public UUID softwareAppId(String publicId) {
        return catalog.softwareAppId(publicId);
    }

    private ExportCandidate mapExportCandidate(ResultSet rs, int rowNum) throws SQLException {
        return new ExportCandidate(
                rs.getString("app_key"),
                rs.getString("name"),
                rs.getString("winstall_id"),
                rs.getString("official_url"),
                rs.getString("operating_system"),
                rs.getString("extension"),
                rs.getString("resolved_url_encrypted"));
    }

    private void deleteApps(String appWhereClause, List<Object> appWhereParams) {
        String scopedApps = appWhereClause.isBlank()
                ? "SELECT id FROM software_apps"
                : "SELECT id FROM software_apps " + appWhereClause;
        String scopedSources = "SELECT id FROM download_sources WHERE software_app_id IN (" + scopedApps + ")";
        Object[] params = appWhereParams.toArray();

        jdbc.update("DELETE FROM resolver_logs WHERE download_source_id IN (" + scopedSources + ")", params);
        jdbc.update("DELETE FROM resolved_sources WHERE download_source_id IN (" + scopedSources + ")", params);
        jdbc.update("DELETE FROM download_sources WHERE software_app_id IN (" + scopedApps + ")", params);
        jdbc.update("DELETE FROM software_app_tags WHERE software_app_id IN (" + scopedApps + ")", params);
        jdbc.update("DELETE FROM bundle_items WHERE software_app_id IN (" + scopedApps + ")", params);
        jdbc.update("DELETE FROM software_apps " + appWhereClause, params);
    }

    private void assertScraperIdleForDeletion() {
        boolean running = !jdbc.queryForList(
                "SELECT id FROM scrape_runs WHERE status = 'running' FOR UPDATE").isEmpty();
        if (running) {
            throw scraperRunningConflict();
        }
        boolean queuedOrActive = !jdbc.queryForList(
                """
                SELECT id
                FROM scraper_work_items
                WHERE status IN ('queued', 'in_progress')
                FOR UPDATE
                """).isEmpty();
        if (queuedOrActive) {
            throw scraperRunningConflict();
        }
    }

    private ConflictException scraperRunningConflict() {
        return new ConflictException(
                "scraper_running",
                "No se pueden eliminar aplicaciones mientras el scraper está en ejecución.");
    }

    private void refreshBundleCounts(List<UUID> bundleIds) {
        for (UUID bundleId : bundleIds.stream().distinct().toList()) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bundle_items WHERE bundle_id = ?",
                    Integer.class,
                    UuidBytes.fromUuid(bundleId));
            jdbc.update(
                    "UPDATE bundles SET app_count = ?, updated_at = ? WHERE id = ?",
                    count == null ? 0 : count,
                    LocalDateTime.now(),
                    UuidBytes.fromUuid(bundleId));
        }
    }

    private void createDefaultSource(UUID appId, String officialUrl, LocalDateTime now) {
        jdbc.update(
                """
                INSERT INTO download_sources
                (id, software_app_id, operating_system, architecture, initial_url, resolver_type,
                 resolver_config, resolution_status, validation_status, version, created_at, updated_at)
                VALUES (?, ?, 'windows', 'x86_64', ?, 'generic_http', JSON_OBJECT('source', 'admin'),
                        'requires_manual_review', 'unchecked', 0, ?, ?)
                """,
                UuidBytes.fromUuid(UUID.randomUUID()),
                UuidBytes.fromUuid(appId),
                officialUrl,
                now,
                now);
    }

    private void replaceTags(UUID appId, List<String> tags, String source) {
        jdbc.update("DELETE FROM software_app_tags WHERE software_app_id = ?", UuidBytes.fromUuid(appId));
        if (tags == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (String tag : tags.stream().filter(value -> !isBlank(value)).distinct().toList()) {
            jdbc.update(
                    """
                    INSERT IGNORE INTO software_app_tags
                    (id, software_app_id, tag, normalized_tag, source, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UuidBytes.fromUuid(UUID.randomUUID()),
                    UuidBytes.fromUuid(appId),
                    tag.trim(),
                    normalizeText(tag),
                    source,
                    now);
        }
    }

    private UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException exception) {
            throw new NotFoundException("source_not_found", "La fuente no existe.");
        }
    }

    private String normalizeSlug(String value) {
        String slug = value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "app-" + UUID.randomUUID() : slug;
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private String coalesce(String next, String current) {
        return next == null ? current : next;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String winstallUrl(String winstallId) {
        if (isBlank(winstallId) || winstallId.startsWith("manual.")) {
            return "None";
        }
        return "https://winstall.app/apps/" + winstallId.trim();
    }

    private String blankToNone(String value) {
        return isBlank(value) ? "None" : value.trim();
    }

    private String platformKey(String operatingSystem, String extension) {
        String os = operatingSystem == null ? "" : operatingSystem.toLowerCase(Locale.ROOT).trim();
        if (os.contains("windows") || os.equals("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("mac") || os.contains("darwin") || os.contains("osx")) {
            return "macos";
        }

        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT).replace(".", "").trim();
        return switch (ext) {
            case "exe", "msi", "msix", "appx" -> "windows";
            case "deb", "rpm", "appimage", "flatpak" -> "linux";
            case "dmg", "pkg" -> "macos";
            default -> null;
        };
    }

    private String csvCell(String value) {
        String safe = isBlank(value) ? "None" : value;
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }

    public record AppCsvExport(String content, int rowCount) {}

    private record ExportCandidate(
            String appKey,
            String name,
            String winstallId,
            String officialUrl,
            String operatingSystem,
            String extension,
            String encryptedUrl) {}

    private static final class ExportRow {
        private final String name;
        private final String winstall;
        private final String officialUrl;
        private String windows = "None";
        private String linux = "None";
        private String macos = "None";

        private ExportRow(String name, String winstall, String officialUrl) {
            this.name = name;
            this.winstall = winstall;
            this.officialUrl = officialUrl;
        }

        private void putIfMissing(String platform, String url) {
            if ("windows".equals(platform) && "None".equals(windows)) {
                windows = url;
            } else if ("linux".equals(platform) && "None".equals(linux)) {
                linux = url;
            } else if ("macos".equals(platform) && "None".equals(macos)) {
                macos = url;
            }
        }

        private String name() {
            return name;
        }

        private String winstall() {
            return winstall;
        }

        private String officialUrl() {
            return officialUrl;
        }

        private String windows() {
            return windows;
        }

        private String linux() {
            return linux;
        }

        private String macos() {
            return macos;
        }
    }
}
