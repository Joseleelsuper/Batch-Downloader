package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JpaCatalogSourceLookup implements CatalogSourceLookup {
    private static final List<String> DEFAULT_OPERATING_SYSTEMS = List.of("windows", "linux", "macos");
    private final JdbcTemplate jdbc;

    JpaCatalogSourceLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, VerifiedSource> findVerifiedSources(
            Collection<UUID> appIds, List<String> operatingSystems) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<String> systems = normalizedSystems(operatingSystems);
        StringBuilder sql = new StringBuilder("""
                SELECT ds.software_app_id, rs.id AS source_ref, ds.operating_system, ds.architecture
                FROM software_apps app
                JOIN download_sources ds ON ds.software_app_id = app.id
                JOIN resolved_sources rs ON rs.download_source_id = ds.id
                WHERE ds.software_app_id IN (
                """);
        appendPlaceholders(sql, ids.size());
        sql.append("""
                )
                  AND app.app_status = 'active'
                  AND app.catalog_status = 'available'
                  AND ds.resolution_status IN ('direct', 'fallback')
                  AND ds.validation_status = 'valid'
                  AND ds.catalog_available = 1
                  AND rs.catalog_downloadable = 1
                  AND ds.operating_system IN (
                """);
        appendPlaceholders(sql, systems.size());
        sql.append("""
                )
                ORDER BY ds.software_app_id,
                         FIELD(ds.operating_system, 'windows', 'linux', 'macos') ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 2147483647) ASC,
                         rs.score DESC,
                         rs.checked_at DESC,
                         rs.id ASC
                """);
        List<Object> parameters = new ArrayList<>(ids.size() + systems.size());
        ids.forEach(id -> parameters.add(UuidBytes.fromUuid(id)));
        // Expiry only triggers the scraper's mandatory JIT revalidation. It does
        // not remove an otherwise valid candidate from the public catalog.
        parameters.addAll(systems);
        Map<UUID, VerifiedSource> selected = new LinkedHashMap<>();
        jdbc.query(sql.toString(), (ResultSet row) -> {
            UUID appId = UuidBytes.toUuid(row.getBytes("software_app_id"));
            selected.putIfAbsent(appId, new VerifiedSource(
                    appId,
                    UuidBytes.toUuid(row.getBytes("source_ref")),
                    row.getString("operating_system"),
                    row.getString("architecture")));
        }, parameters.toArray());
        return Map.copyOf(selected);
    }

    private static List<String> normalizedSystems(List<String> operatingSystems) {
        if (operatingSystems == null || operatingSystems.isEmpty()) {
            return DEFAULT_OPERATING_SYSTEMS;
        }
        // Keep the preference stable regardless of the order in which an HTTP
        // client serializes the selected systems. The job model stores one
        // executable per app, so a request with several systems selects the
        // first verified platform in this canonical order; apps with none of
        // those platforms are omitted by the application service.
        List<String> filtered = DEFAULT_OPERATING_SYSTEMS.stream()
                .filter(operatingSystems::contains)
                .toList();
        return filtered.isEmpty() ? DEFAULT_OPERATING_SYSTEMS : filtered;
    }

    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) sql.append(", ");
            sql.append('?');
        }
    }
}
