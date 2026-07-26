package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogChangeEvent;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogFacetsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.DownloadOption;
import es.ubu.batchdownloader.catalog.CatalogDtos.FacetItem;
import es.ubu.batchdownloader.catalog.CatalogDtos.LastScrapeRun;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.text.Normalizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    private static final String REVIEW_LAST_ORDER =
            "CASE WHEN a.catalog_status = 'review' THEN 1 ELSE 0 END ASC";
    private static final Set<String> CATALOG_STATUSES = Set.of("all", "available", "review", "missing");

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AppListItem> search(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode,
            String sort,
            int page,
            int pageSize) {
        return search(
                query,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers,
                tagMatchMin,
                tagMode,
                sort,
                page,
                pageSize,
                SemanticCandidateSet.lexical());
    }

    public List<AppListItem> search(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode,
            String sort,
            int page,
            int pageSize,
            SemanticCandidateSet candidates) {
        if (candidates.semantic()) {
            return semanticSearch(
                    query,
                    status,
                    operatingSystems,
                    architecture,
                    tags,
                    publishers,
                    tagMatchMin,
                    tagMode,
                    sort,
                    page,
                    pageSize,
                    candidates);
        }
        status = normalizeCatalogStatus(status);
        SearchRanking ranking = SearchRanking.from(query);
        String innerOrderBy = orderBy(sort, ranking.innerPrefix());
        String outerOrderBy = orderBy(sort, ranking.outerPrefix());
        StringBuilder sql = new StringBuilder("""
                SELECT a.*
                FROM software_apps a
                JOIN (
                    SELECT a.id
                """);
        List<Object> params = new ArrayList<>();
        if (ranking.active()) {
            sql.append(", ").append(ranking.scoreSql()).append(" AS search_score\n");
            params.addAll(ranking.params());
        }
        sql.append("""
                    FROM software_apps a
                    WHERE a.app_status = 'active'
                """);
        appendFilters(sql, params, query, status, operatingSystems, architecture, tags, publishers, tagMatchMin, tagMode);
        sql.append(" ORDER BY ").append(innerOrderBy);
        sql.append(" LIMIT ? OFFSET ?) page ON page.id = a.id ORDER BY ").append(outerOrderBy);
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        List<AppBasics> apps = jdbc.query(sql.toString(), (rs, rowNum) -> readBasics(rs), params.toArray());
        List<UUID> appIds = apps.stream().map(AppBasics::dbId).toList();
        Map<UUID, List<String>> systemsByApp = operatingSystemsFor(appIds);
        Map<UUID, List<String>> tagsByApp = tagsFor(appIds);
        Map<UUID, SourceSnapshot> sourcesByApp = sourcesFor(appIds);
        return apps.stream()
                .map(app -> mapListItem(
                        app,
                        systemsByApp.getOrDefault(app.dbId(), List.of()),
                        tagsByApp.getOrDefault(app.dbId(), List.of()),
                        sourcesByApp.getOrDefault(app.dbId(), SourceSnapshot.empty())
                                .effectiveFor(app.catalogStatus())))
                .toList();
    }

    public long count(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode) {
        return count(
                query,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers,
                tagMatchMin,
                tagMode,
                SemanticCandidateSet.lexical());
    }

    public long count(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode,
            SemanticCandidateSet candidates) {
        if (candidates.semantic()) {
            return semanticCount(
                    query,
                    status,
                    operatingSystems,
                    architecture,
                    tags,
                    publishers,
                    tagMatchMin,
                    tagMode,
                    candidates);
        }
        status = normalizeCatalogStatus(status);
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM software_apps a
                WHERE a.app_status = 'active'
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, query, status, operatingSystems, architecture, tags, publishers, tagMatchMin, tagMode);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    public CatalogFacetsResponse facets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode) {
        return facets(
                query,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers,
                tagMatchMin,
                tagMode,
                SemanticCandidateSet.lexical());
    }

    public CatalogFacetsResponse facets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode,
            SemanticCandidateSet candidates) {
        if (candidates.semantic()) {
            return semanticFacets(
                    query,
                    status,
                    operatingSystems,
                    architecture,
                    tags,
                    publishers,
                    tagMatchMin,
                    tagMode,
                    candidates);
        }
        status = normalizeCatalogStatus(status);
        return new CatalogFacetsResponse(
                tagFacets(query, status, operatingSystems, architecture, publishers),
                publisherFacets(query, status, operatingSystems, architecture, tags, tagMatchMin, tagMode));
    }

    private List<AppListItem> semanticSearch(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode,
            String sort,
            int page,
            int pageSize,
            SemanticCandidateSet candidates) {
        status = normalizeCatalogStatus(status);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(semanticCandidateCte(query, candidates, params));
        sql.append("""
                SELECT a.*
                FROM software_apps a
                JOIN (
                    SELECT a.id, ranked.semantic_rank
                    FROM software_apps a
                    JOIN semantic_candidates ranked ON ranked.id = a.id
                    WHERE a.app_status = 'active'
                """);
        appendStructuredFilters(
                sql,
                params,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers,
                tagMatchMin,
                tagMode);
        sql.append(" ORDER BY ")
                .append(orderBy(sort, "ranked.semantic_rank ASC, "))
                .append(" LIMIT ? OFFSET ?) page ON page.id = a.id ORDER BY ")
                .append(orderBy(sort, "page.semantic_rank ASC, "));
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        List<AppBasics> apps = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> readBasics(rs),
                params.toArray());
        List<UUID> appIds = apps.stream().map(AppBasics::dbId).toList();
        Map<UUID, List<String>> systemsByApp = operatingSystemsFor(appIds);
        Map<UUID, List<String>> tagsByApp = tagsFor(appIds);
        Map<UUID, SourceSnapshot> sourcesByApp = sourcesFor(appIds);
        return apps.stream()
                .map(app -> mapListItem(
                        app,
                        systemsByApp.getOrDefault(app.dbId(), List.of()),
                        tagsByApp.getOrDefault(app.dbId(), List.of()),
                        sourcesByApp.getOrDefault(app.dbId(), SourceSnapshot.empty())
                                .effectiveFor(app.catalogStatus())))
                .toList();
    }

    private long semanticCount(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode,
            SemanticCandidateSet candidates) {
        status = normalizeCatalogStatus(status);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(semanticCandidateCte(query, candidates, params));
        sql.append("""
                SELECT COUNT(*)
                FROM software_apps a
                JOIN semantic_candidates ranked ON ranked.id = a.id
                WHERE a.app_status = 'active'
                """);
        appendStructuredFilters(
                sql,
                params,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers,
                tagMatchMin,
                tagMode);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    private CatalogFacetsResponse semanticFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode,
            SemanticCandidateSet candidates) {
        status = normalizeCatalogStatus(status);
        return new CatalogFacetsResponse(
                semanticTagFacets(
                        query,
                        status,
                        operatingSystems,
                        architecture,
                        publishers,
                        candidates),
                semanticPublisherFacets(
                        query,
                        status,
                        operatingSystems,
                        architecture,
                        tags,
                        tagMatchMin,
                        tagMode,
                        candidates));
    }

    private List<FacetItem> semanticTagFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> publishers,
            SemanticCandidateSet candidates) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(semanticCandidateCte(query, candidates, params));
        sql.append("""
                SELECT MIN(t.tag) AS label, t.normalized_tag AS normalized_value,
                       COUNT(DISTINCT a.id) AS app_count
                FROM software_app_tags t
                JOIN software_apps a ON a.id = t.software_app_id
                JOIN semantic_candidates ranked ON ranked.id = a.id
                WHERE a.app_status = 'active'
                """);
        appendStructuredFilters(
                sql,
                params,
                status,
                operatingSystems,
                architecture,
                List.of(),
                publishers,
                null,
                "all");
        sql.append("""
                GROUP BY t.normalized_tag
                ORDER BY app_count DESC, label ASC
                """);
        return jdbc.query(
                sql.toString(),
                (rs, rowNum) -> facetItem(
                        rs.getString("label"),
                        rs.getString("normalized_value"),
                        rs.getLong("app_count")),
                params.toArray());
    }

    private List<FacetItem> semanticPublisherFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            Integer tagMatchMin,
            String tagMode,
            SemanticCandidateSet candidates) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(semanticCandidateCte(query, candidates, params));
        sql.append("""
                SELECT a.publisher AS label, LOWER(TRIM(a.publisher)) AS normalized_value,
                       COUNT(DISTINCT a.id) AS app_count
                FROM software_apps a
                JOIN semantic_candidates ranked ON ranked.id = a.id
                WHERE a.app_status = 'active'
                  AND a.publisher IS NOT NULL
                  AND TRIM(a.publisher) <> ''
                """);
        appendStructuredFilters(
                sql,
                params,
                status,
                operatingSystems,
                architecture,
                tags,
                List.of(),
                tagMatchMin,
                tagMode);
        sql.append("""
                GROUP BY a.publisher
                ORDER BY app_count DESC, label ASC
                """);
        return jdbc.query(
                sql.toString(),
                (rs, rowNum) -> facetItem(
                        rs.getString("label"),
                        rs.getString("normalized_value"),
                        rs.getLong("app_count")),
                params.toArray());
    }

    private String semanticCandidateCte(
            String query,
            SemanticCandidateSet candidates,
            List<Object> params) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("semantic_search_requires_query");
        }
        params.add(candidates.candidatesJson());
        return """
                WITH semantic_candidates AS (
                    SELECT UUID_TO_BIN(candidate.app_id) AS id,
                           candidate.semantic_rank
                    FROM JSON_TABLE(
                        ?,
                        '$[*]' COLUMNS(
                            app_id VARCHAR(36) PATH '$.appId',
                            semantic_rank INT PATH '$.rank'
                        )
                    ) AS candidate
                )
                """;
    }

    private List<FacetItem> tagFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> publishers) {
        StringBuilder sql = new StringBuilder("""
                SELECT MIN(t.tag) AS label, t.normalized_tag AS normalized_value, COUNT(DISTINCT a.id) AS app_count
                FROM software_app_tags t
                JOIN software_apps a ON a.id = t.software_app_id
                WHERE a.app_status = 'active'
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, query, status, operatingSystems, architecture, List.of(), publishers, null, "all");
        sql.append("""
                GROUP BY t.normalized_tag
                ORDER BY app_count DESC, label ASC
                """);
        return jdbc.query(sql.toString(), (rs, rowNum) -> facetItem(
                rs.getString("label"),
                rs.getString("normalized_value"),
                rs.getLong("app_count")), params.toArray());
    }

    private List<FacetItem> publisherFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            Integer tagMatchMin,
            String tagMode) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.publisher AS label, LOWER(TRIM(a.publisher)) AS normalized_value, COUNT(DISTINCT a.id) AS app_count
                FROM software_apps a
                WHERE a.app_status = 'active'
                  AND a.publisher IS NOT NULL
                  AND TRIM(a.publisher) <> ''
                """);
        List<Object> params = new ArrayList<>();
        appendFilters(sql, params, query, status, operatingSystems, architecture, tags, List.of(), tagMatchMin, tagMode);
        sql.append("""
                GROUP BY a.publisher
                ORDER BY app_count DESC, label ASC
                """);
        return jdbc.query(sql.toString(), (rs, rowNum) -> facetItem(
                rs.getString("label"),
                rs.getString("normalized_value"),
                rs.getLong("app_count")), params.toArray());
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
        StatsSnapshot snapshot = jdbc.queryForObject("""
                SELECT total_count, available_count, review_count, missing_count
                FROM catalog_counters
                WHERE id = ?
                """, (rs, rowNum) -> new StatsSnapshot(
                        rs.getLong("total_count"),
                        rs.getLong("available_count"),
                        rs.getLong("review_count"),
                        rs.getLong("missing_count")),
                1);
        Map<String, Long> filters = new LinkedHashMap<>();
        filters.put("all", snapshot.total());
        filters.put("available", snapshot.available());
        filters.put("review", snapshot.review());
        filters.put("missing", snapshot.missing());
        LastScrapeRun last = latestRun();
        return new CatalogStatsResponse(snapshot.total(), filters, last, LocalDateTime.now());
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
        String catalogToken = jdbc.queryForObject(
                """
                SELECT CONCAT(version, ':', total_count, ':', available_count, ':', review_count, ':', missing_count)
                FROM catalog_counters
                WHERE id = ?
                """,
                String.class,
                1);
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
        return Integer.toHexString(((appToken == null ? "" : appToken)
                + "|" + (catalogToken == null ? "" : catalogToken)
                + "|" + (runTokens.isEmpty() ? "" : runTokens.get(0))).hashCode());
    }

    private void appendFilters(
            StringBuilder sql,
            List<Object> params,
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode) {
        appendLexicalFilter(sql, params, query);
        appendStructuredFilters(
                sql,
                params,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers,
                tagMatchMin,
                tagMode);
    }

    private void appendLexicalFilter(
            StringBuilder sql,
            List<Object> params,
            String query) {
        if (query != null && !query.isBlank()) {
            String normalized = normalizeSearchQuery(query);
            String normalizedLike = "%" + normalized + "%";
            String compactLike = "%" + compactSearchQuery(normalized) + "%";
            String rawLike = "%" + query.toLowerCase(Locale.ROOT).trim() + "%";
            sql.append("""
                    AND (
                        a.normalized_name LIKE ? OR LOWER(a.name) LIKE ? OR
                        REPLACE(a.normalized_name, ' ', '') LIKE ? OR
                        LOWER(a.publisher) LIKE ? OR
                        LOWER(a.description) LIKE ? OR LOWER(a.long_description) LIKE ? OR
                        LOWER(a.winstall_id) LIKE ? OR LOWER(REPLACE(a.winstall_id, '.', '')) LIKE ? OR
                        EXISTS (
                            SELECT 1 FROM software_app_tags sat
                            WHERE sat.software_app_id = a.id AND sat.normalized_tag LIKE ?
                        )
                    )
                    """);
            params.add(normalizedLike);
            params.add(rawLike);
            params.add(compactLike);
            params.add(rawLike);
            params.add(rawLike);
            params.add(rawLike);
            params.add(rawLike);
            params.add(compactLike);
            params.add(normalizedLike);
        }
    }

    private void appendStructuredFilters(
            StringBuilder sql,
            List<Object> params,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            Integer tagMatchMin,
            String tagMode) {
        appendSourceFilter(sql, params, status, operatingSystems, architecture);
        List<String> normalizedPublishers = normalizedDistinct(publishers);
        if (!normalizedPublishers.isEmpty()) {
            sql.append(" AND LOWER(TRIM(COALESCE(a.publisher, ''))) IN (");
            appendPlaceholders(sql, normalizedPublishers.size());
            sql.append(")");
            params.addAll(normalizedPublishers);
        }

        List<String> normalizedTags = normalizedDistinct(tags);
        if (!normalizedTags.isEmpty()) {
            int requiredMatches = requiredTagMatches(normalizedTags.size(), tagMatchMin, tagMode);
            sql.append(" AND (SELECT COUNT(DISTINCT t.normalized_tag) FROM software_app_tags t WHERE t.software_app_id = a.id AND t.normalized_tag IN (");
            appendPlaceholders(sql, normalizedTags.size());
            sql.append(")) >= ?\n");
            params.addAll(normalizedTags);
            params.add(requiredMatches);
        }
    }

    private void appendSourceFilter(
            StringBuilder sql,
            List<Object> params,
            String status,
            List<String> operatingSystems,
            String architecture) {
        boolean hasStatus = !"all".equals(status);
        boolean hasOperatingSystem = operatingSystems != null && !operatingSystems.isEmpty();
        boolean hasArchitecture = architecture != null && !architecture.isBlank();
        if (hasOperatingSystem) {
            sql.append(" AND (");
            for (int index = 0; index < operatingSystems.size(); index++) {
                if (index > 0) sql.append(" OR ");
                sql.append("JSON_CONTAINS(COALESCE(a.operating_systems_json, JSON_ARRAY()), JSON_QUOTE(?))");
                params.add(operatingSystems.get(index));
            }
            sql.append(")");
        }
        if (hasStatus) {
            sql.append(" AND a.catalog_status = ?");
            params.add(status);
        }
        if (hasArchitecture) {
            sql.append("""
                    AND EXISTS (
                        SELECT 1 FROM download_sources architecture_source
                        WHERE architecture_source.software_app_id = a.id
                          AND architecture_source.architecture = ?
                    )
                    """);
            params.add(architecture);
        }
    }

    private AppListItem mapListItem(
            AppBasics app,
            List<String> operatingSystems,
            List<String> tags,
            SourceSnapshot source) {
        return new AppListItem(
                app.dbId().toString(),
                app.slug(),
                app.winstallId(),
                app.name(),
                app.publisher(),
                app.description(),
                app.longDescription(),
                tags,
                operatingSystems,
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
        SourceSnapshot source = sourceFor(app.dbId()).effectiveFor(app.catalogStatus());
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
                operatingSystemsFor(List.of(app.dbId())).getOrDefault(app.dbId(), List.of()),
                app.iconUrl(),
                app.officialUrl(),
                winstallAppUrl(app.winstallId()),
                app.latestVersion(),
                source.filename(),
                source.extension() == null ? null : source.extension().replace(".", "").toUpperCase(Locale.ROOT),
                source.contentType(),
                source.sizeBytes(),
                source.finalDomain(),
                source.score(),
                source.resolutionStatus(),
                source.validationStatus(),
                source.downloadable(),
                app.updatedAt(),
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
                rs.getString("catalog_status"),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private SourceSnapshot sourceFor(UUID appId) {
        List<SourceSnapshot> snapshots = jdbc.query(
                """
                SELECT ds.id AS source_id, ds.initial_url,
                       ds.resolution_status AS source_resolution_status,
                       ds.validation_status AS source_validation_status,
                       rs.id AS resolved_id, rs.filename, rs.extension, rs.content_type, rs.size_bytes,
                       rs.final_domain, rs.score, rs.checked_at, rs.expires_at, rs.metadata_json,
                       rs.release_rank, rs.is_latest
                FROM download_sources ds
                LEFT JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    AND rs.catalog_downloadable = 1
                WHERE ds.software_app_id = ?
                ORDER BY ds.catalog_available DESC,
                         (ds.resolution_status = 'requires_manual_review') DESC,
                         (ds.resolution_status IN ('missing', 'broken')) DESC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC, rs.checked_at DESC, ds.id ASC, rs.id ASC
                LIMIT 1
                """,
                (rs, rowNum) -> readSourceSnapshot(rs),
                UuidBytes.fromUuid(appId));
        return snapshots.isEmpty()
                ? SourceSnapshot.empty()
                : snapshots.get(0);
    }

    private Map<UUID, SourceSnapshot> sourcesFor(Collection<UUID> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().distinct().toList();
        StringBuilder sql = new StringBuilder("""
                SELECT ds.software_app_id, ds.id AS source_id, ds.initial_url,
                       ds.resolution_status AS source_resolution_status,
                       ds.validation_status AS source_validation_status,
                       rs.id AS resolved_id, rs.filename, rs.extension, rs.content_type, rs.size_bytes,
                       rs.final_domain, rs.score, rs.checked_at, rs.expires_at, rs.metadata_json,
                       rs.release_rank, rs.is_latest
                FROM download_sources ds
                LEFT JOIN resolved_sources rs ON rs.download_source_id = ds.id
                    AND rs.catalog_downloadable = 1
                WHERE ds.software_app_id IN (
                """);
        appendPlaceholders(sql, ids.size());
        sql.append("""
                )
                ORDER BY ds.software_app_id,
                         ds.catalog_available DESC,
                         (ds.resolution_status = 'requires_manual_review') DESC,
                         (ds.resolution_status IN ('missing', 'broken')) DESC,
                         rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC, rs.checked_at DESC, ds.id ASC, rs.id ASC
                """);
        Map<UUID, SourceSnapshot> result = new HashMap<>();
        List<Object> parameters = new ArrayList<>(ids.size());
        ids.stream().map(UuidBytes::fromUuid).forEach(parameters::add);
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> result.putIfAbsent(
                UuidBytes.toUuid(rs.getBytes("software_app_id")), readSourceSnapshot(rs)),
                parameters.toArray());
        return result;
    }

    private SourceSnapshot readSourceSnapshot(ResultSet rs) throws SQLException {
        String resolution = rs.getString("source_resolution_status");
        String validation = rs.getString("source_validation_status");
        boolean downloadable = rs.getBytes("resolved_id") != null
                && "valid".equals(validation)
                && ("direct".equals(resolution) || "fallback".equals(resolution));
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
    }

    private List<DownloadOption> downloadOptions(UUID appId) {
        return jdbc.query(
                """
                SELECT rs.id, rs.filename, rs.extension, rs.final_domain, rs.score, rs.status, rs.metadata_json,
                       ds.operating_system, ds.architecture, rs.version, rs.is_latest, rs.version_status,
                       rs.release_rank
                FROM download_sources ds
                JOIN resolved_sources rs ON rs.download_source_id = ds.id
                WHERE ds.software_app_id = ?
                  AND ds.catalog_available = 1
                  AND rs.catalog_downloadable = 1
                ORDER BY rs.is_latest DESC,
                         COALESCE(rs.release_rank, 9999) ASC,
                         (JSON_UNQUOTE(JSON_EXTRACT(rs.metadata_json, '$.is_primary')) = 'true') DESC,
                         rs.score DESC, rs.checked_at DESC
                LIMIT 50
                """,
                (rs, rowNum) -> new DownloadOption(
                        UuidBytes.toUuid(rs.getBytes("id")).toString(),
                        rs.getString("filename"),
                        rs.getString("extension"),
                        rs.getString("operating_system"),
                        rs.getString("architecture"),
                        rs.getString("version"),
                        rs.getBoolean("is_latest"),
                        rs.getString("version_status"),
                        sourceLabel(rs.getString("status")),
                        rs.getInt("score"),
                        rs.getString("final_domain"),
                        rowNum == 0),
                UuidBytes.fromUuid(appId));
    }

    private Map<UUID, List<String>> operatingSystemsFor(Collection<UUID> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().distinct().toList();
        StringBuilder sql = new StringBuilder("""
                SELECT a.id AS software_app_id, projected.operating_system
                FROM software_apps a
                CROSS JOIN JSON_TABLE(
                    COALESCE(a.operating_systems_json, JSON_ARRAY()),
                    '$[*]' COLUMNS(operating_system VARCHAR(16) PATH '$')
                ) AS projected
                WHERE a.id IN (
                """);
        appendPlaceholders(sql, ids.size());
        sql.append("""
                )
                  AND projected.operating_system IN ('windows', 'linux', 'macos')
                ORDER BY a.id, FIELD(projected.operating_system, 'windows', 'linux', 'macos')
                """);
        Map<UUID, List<String>> result = new HashMap<>();
        jdbc.query(sql.toString(), row -> {
            UUID appId = UuidBytes.toUuid(row.getBytes("software_app_id"));
            result.computeIfAbsent(appId, ignored -> new ArrayList<>()).add(row.getString("operating_system"));
        }, ids.stream().map(UuidBytes::fromUuid).toArray());
        result.replaceAll((id, systems) -> systems.stream().distinct().toList());
        return result;
    }

    private List<String> tagsFor(UUID appId) {
        return jdbc.queryForList(
                "SELECT tag FROM software_app_tags WHERE software_app_id = ? ORDER BY tag",
                String.class,
                UuidBytes.fromUuid(appId));
    }

    private Map<UUID, List<String>> tagsFor(Collection<UUID> appIds) {
        if (appIds == null || appIds.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = appIds.stream().distinct().toList();
        StringBuilder sql = new StringBuilder("""
                SELECT software_app_id, tag
                FROM software_app_tags
                WHERE software_app_id IN (
                """);
        appendPlaceholders(sql, ids.size());
        sql.append(") ORDER BY software_app_id, tag");
        Map<UUID, List<String>> result = new HashMap<>();
        jdbc.query(sql.toString(), (RowCallbackHandler) rs -> result
                        .computeIfAbsent(UuidBytes.toUuid(rs.getBytes("software_app_id")), ignored -> new ArrayList<>())
                        .add(rs.getString("tag")),
                ids.stream().map(UuidBytes::fromUuid).toArray());
        result.replaceAll((id, tags) -> List.copyOf(tags));
        return result;
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

    private String orderBy(String sort, String prefix) {
        String tieBreaker = switch (sort) {
            case "updated" -> "a.updated_at DESC, a.normalized_name ASC, a.id ASC";
            case "downloads" -> "a.download_count DESC, a.normalized_name ASC, a.id ASC";
            default -> "a.normalized_name ASC, a.id ASC";
        };
        String relevanceOrder = prefix == null || prefix.isBlank() ? "" : prefix;
        return reviewLastOrder() + ", " + relevanceOrder + tieBreaker;
    }

    /**
     * Keeps apps that only have a manual-review source out of the main catalog
     * pages. The predicate deliberately matches the review filter semantics:
     * an app with any valid direct or fallback source remains in its natural
     * name/update position.
     */
    private String reviewLastOrder() {
        return REVIEW_LAST_ORDER;
    }

    private void appendPlaceholders(StringBuilder sql, int count) {
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
    }

    private List<String> normalizedDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.toLowerCase(Locale.ROOT).trim());
        }
        return List.copyOf(normalized);
    }

    static String normalizeSearchQuery(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .trim();
        return normalized.replaceAll("\\s+", " ");
    }

    static String normalizeCatalogStatus(String status) {
        String normalized = status == null || status.isBlank()
                ? "all"
                : status.trim().toLowerCase(Locale.ROOT);
        if (!CATALOG_STATUSES.contains(normalized)) {
            throw new BadRequestException(
                    "invalid_catalog_status",
                    "El estado de catálogo indicado no es válido.");
        }
        return normalized;
    }

    private static String compactSearchQuery(String normalized) {
        return normalized == null ? "" : normalized.replaceAll("\\s+", "");
    }

    static int requiredTagMatches(int selectedTags, Integer requestedMinimum, String tagMode) {
        if (selectedTags < 1) {
            return 0;
        }
        if (requestedMinimum != null) {
            return Math.max(1, Math.min(requestedMinimum, selectedTags));
        }
        return "any".equals(tagMode) ? 1 : selectedTags;
    }

    private FacetItem facetItem(String label, String normalizedValue, long count) {
        String safeLabel = label == null || label.isBlank() ? "-" : label.trim();
        String safeNormalized = normalizedValue == null || normalizedValue.isBlank()
                ? safeLabel.toLowerCase(Locale.ROOT)
                : normalizedValue.trim();
        return new FacetItem(safeLabel, safeLabel, safeNormalized, facetLetter(safeLabel), count);
    }

    static String facetLetter(String value) {
        if (value == null || value.isBlank()) {
            return "#";
        }
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            if (Character.isDigit(codePoint)) {
                return "#";
            }
            char upper = Character.toUpperCase((char) codePoint);
            if (upper >= 'A' && upper <= 'Z') {
                return Character.toString(upper);
            }
            if (Character.isLetter(codePoint)) {
                return "#";
            }
        }
        return "#";
    }

    private String sourceLabel(String status) {
        if ("direct".equals(status)) {
            return "Sitio oficial";
        }
        if ("fallback".equals(status)) {
            return "Fallback Winstall";
        }
        if ("requires_manual_review".equals(status)) {
            return "Revisión";
        }
        return "No disponible";
    }

    private String winstallAppUrl(String winstallId) {
        return "https://winstall.app/apps/" + winstallId;
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

    record SearchRanking(boolean active, String scoreSql, List<Object> params) {
        static SearchRanking from(String query) {
            String normalized = normalizeSearchQuery(query);
            if (normalized.isBlank()) {
                return new SearchRanking(false, "", List.of());
            }
            String compact = compactSearchQuery(normalized);
            String lowerRaw = query.toLowerCase(Locale.ROOT).trim();
            String normalizedPrefix = normalized + "%";
            String normalizedContains = "%" + normalized + "%";
            String compactPrefix = compact + "%";
            String compactContains = "%" + compact + "%";
            String rawPrefix = lowerRaw + "%";
            String rawContains = "%" + lowerRaw + "%";

            List<Object> params = new ArrayList<>();
            StringBuilder sql = new StringBuilder("""
                    (
                        CASE WHEN a.normalized_name = ? THEN 10000 ELSE 0 END
                      + CASE WHEN a.normalized_name LIKE ? THEN 9000 ELSE 0 END
                      + CASE WHEN a.normalized_name LIKE ? THEN 7600 ELSE 0 END
                      + CASE WHEN REPLACE(a.normalized_name, ' ', '') = ? THEN 7300 ELSE 0 END
                      + CASE WHEN REPLACE(a.normalized_name, ' ', '') LIKE ? THEN 6800 ELSE 0 END
                      + CASE WHEN LOWER(TRIM(COALESCE(a.publisher, ''))) = ? THEN 3400 ELSE 0 END
                      + CASE WHEN LOWER(TRIM(COALESCE(a.publisher, ''))) LIKE ? THEN 2600 ELSE 0 END
                      + CASE WHEN LOWER(a.winstall_id) LIKE ? THEN 2200 ELSE 0 END
                      + CASE WHEN LOWER(REPLACE(a.winstall_id, '.', '')) LIKE ? THEN 2200 ELSE 0 END
                      + CASE WHEN EXISTS (
                            SELECT 1 FROM software_app_tags sat_rank_exact
                            WHERE sat_rank_exact.software_app_id = a.id
                              AND sat_rank_exact.normalized_tag = ?
                        ) THEN 1700 ELSE 0 END
                      + CASE WHEN EXISTS (
                            SELECT 1 FROM software_app_tags sat_rank_like
                            WHERE sat_rank_like.software_app_id = a.id
                              AND sat_rank_like.normalized_tag LIKE ?
                        ) THEN 900 ELSE 0 END
                      + CASE WHEN LOWER(COALESCE(a.description, '')) LIKE ? THEN 250 ELSE 0 END
                      + CASE WHEN LOWER(COALESCE(a.long_description, '')) LIKE ? THEN 150 ELSE 0 END
                    """);
            params.add(normalized);
            params.add(normalizedPrefix);
            params.add(normalizedContains);
            params.add(compact);
            params.add(compactPrefix);
            params.add(lowerRaw);
            params.add(rawPrefix);
            params.add(rawContains);
            params.add(compactContains);
            params.add(normalized);
            params.add(normalizedContains);
            params.add(rawContains);
            params.add(rawContains);

            for (String token : searchTokens(normalized)) {
                sql.append("""
                      + CASE WHEN a.normalized_name LIKE ? THEN 80 ELSE 0 END
                      + CASE WHEN LOWER(TRIM(COALESCE(a.publisher, ''))) LIKE ? THEN 25 ELSE 0 END
                    """);
                String tokenLike = "%" + token + "%";
                params.add(tokenLike);
                params.add(tokenLike);
            }
            sql.append(")");
            return new SearchRanking(true, sql.toString(), List.copyOf(params));
        }

        String innerPrefix() {
            return active ? "search_score DESC, " : "";
        }

        String outerPrefix() {
            return active ? "page.search_score DESC, " : "";
        }
    }

    private static List<String> searchTokens(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
            if (tokens.size() >= 6) {
                break;
            }
        }
        return List.copyOf(tokens);
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
            String catalogStatus,
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
        SourceSnapshot effectiveFor(String catalogStatus) {
            if ("available".equals(catalogStatus) && downloadable) {
                return this;
            }
            if ("review".equals(catalogStatus)) {
                if ("requires_manual_review".equals(resolutionStatus)) {
                    return this;
                }
                return new SourceSnapshot(
                        "requires_manual_review", "unchecked", "Revisión", originUrl,
                        null, null, null, null, null, null, null, null, false);
            }
            if ("missing".equals(catalogStatus)) {
                if (("missing".equals(resolutionStatus) || "broken".equals(resolutionStatus)) && !downloadable) {
                    return this;
                }
                return new SourceSnapshot(
                        "missing", "unchecked", "No disponible", originUrl,
                        null, null, null, null, null, null, null, null, false);
            }
            return this;
        }

        static SourceSnapshot empty() {
            return new SourceSnapshot(
                    "missing", "unchecked", "No disponible", null, null, null, null,
                    null, null, null, null, null, false);
        }
    }

    private record StatsSnapshot(long total, long available, long review, long missing) {}
}
