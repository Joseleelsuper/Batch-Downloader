package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.PatchAppRequest;
import es.ubu.batchdownloader.admin.AdminDtos.PatchSourceRequest;
import es.ubu.batchdownloader.admin.AdminDtos.UpsertAppRequest;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AdminAppRepository {
    private final JdbcTemplate jdbc;
    private final CatalogRepository catalog;

    public AdminAppRepository(JdbcTemplate jdbc, CatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
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
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM software_apps", Integer.class);
        deleteApps("", List.of());
        jdbc.update("UPDATE bundles SET app_count = 0, updated_at = ? WHERE app_count <> 0", LocalDateTime.now());
        return count == null ? 0 : count;
    }

    public boolean hasRunningScraper() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scrape_runs WHERE status = 'running'",
                Integer.class);
        return count != null && count > 0;
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

    private void deleteApps(String appWhereClause, List<Object> appWhereParams) {
        String scopedApps = appWhereClause.isBlank()
                ? "SELECT id FROM software_apps"
                : "SELECT id FROM software_apps " + appWhereClause;
        String scopedSources = "SELECT id FROM download_sources WHERE software_app_id IN (" + scopedApps + ")";
        Object[] params = appWhereParams.toArray();

        jdbc.update("DELETE FROM resolver_logs WHERE download_source_id IN (" + scopedSources + ")", params);
        jdbc.update("DELETE FROM resolved_sources WHERE download_source_id IN (" + scopedSources + ")", params);
        jdbc.update("DELETE FROM source_allowed_domains WHERE source_id IN (" + scopedSources + ")", params);
        jdbc.update("DELETE FROM download_sources WHERE software_app_id IN (" + scopedApps + ")", params);
        jdbc.update("DELETE FROM software_app_tags WHERE software_app_id IN (" + scopedApps + ")", params);
        jdbc.update("DELETE FROM bundle_items WHERE software_app_id IN (" + scopedApps + ")", params);
        jdbc.update("DELETE FROM software_apps " + appWhereClause, params);
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
}
