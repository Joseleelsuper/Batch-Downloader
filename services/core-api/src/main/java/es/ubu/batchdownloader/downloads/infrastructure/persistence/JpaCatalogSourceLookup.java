package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.common.UuidBytes;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JpaCatalogSourceLookup implements CatalogSourceLookup {
    private static final List<String> DEFAULT_OPERATING_SYSTEMS = List.of("windows", "linux", "macos");
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration revalidationMaxAge;

    JpaCatalogSourceLookup(
            JdbcTemplate jdbc,
            Clock clock,
            @Value("${app.download.source-revalidation-max-age}") Duration revalidationMaxAge) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.revalidationMaxAge = revalidationMaxAge;
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
                FROM download_sources ds
                JOIN resolved_sources rs ON rs.download_source_id = ds.id
                WHERE ds.software_app_id IN (
                """);
        appendPlaceholders(sql, ids.size());
        sql.append("""
                )
                  AND ds.resolution_status IN ('direct', 'fallback')
                  AND ds.validation_status = 'valid'
                  AND rs.status IN ('direct', 'fallback')
                  AND rs.validation_status = 'valid'
                  AND rs.checked_at >= ?
                  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.validation_confidence')), '')
                      IN ('', 'validated', 'verified')
                  AND COALESCE(JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.transport_security')), '')
                      NOT IN ('https_winstall_edge_attested', 'http_winstall_verified')
                  AND ds.operating_system IN (
                """);
        appendPlaceholders(sql, systems.size());
        sql.append("""
                )
                ORDER BY ds.software_app_id,
                         (rs.expires_at > NOW()) DESC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 2147483647) ASC,
                         rs.score DESC,
                         rs.checked_at DESC
                """);
        List<Object> parameters = new ArrayList<>(ids.size() + systems.size() + 1);
        ids.forEach(id -> parameters.add(UuidBytes.fromUuid(id)));
        // A recently verified but TTL-expired resolution remains only a candidate:
        // the scraper's internal endpoint must revalidate it before the worker sees
        // the URL, and the worker validates the binary again while downloading.
        parameters.add(Timestamp.from(clock.instant().minus(revalidationMaxAge)));
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
        List<String> filtered = operatingSystems.stream()
                .filter(DEFAULT_OPERATING_SYSTEMS::contains)
                .distinct()
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
