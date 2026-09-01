package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppListItem;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogAlphabetEntry;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogChangeEvent;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogFacetsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.UuidBytes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Gestiona la persistencia y consulta de {@code CatalogRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
public class CatalogRepository {
    /**
     * Constante que define {@code REVIEW_LAST_ORDER}.
     */
    private static final String REVIEW_LAST_ORDER =
            "a.catalog_review_priority ASC";
    /**
     * Constante que define {@code CATALOG_STATUSES}.
     */
    private static final Set<String> CATALOG_STATUSES =
            Set.of("all", "available", "review", "missing", "unresolved");

    /**
     * Estado {@code jdbc} mantenido por {@code CatalogRepository}.
     */
    private final JdbcTemplate jdbc;
    private final CatalogStatisticsRepository statistics;
    private final CatalogProjectionRepository projections;
    private final CatalogFacetRepository facetRepository;

    /**
     * Inicializa una instancia de {@code CatalogRepository}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @param statistics lecturas cohesionadas de contadores y versiones
     * @param projections proyecciones enriquecidas de filas y detalles
     * @param facetRepository índice alfabético y facetas compatibles
     */
    public CatalogRepository(
            JdbcTemplate jdbc,
            CatalogStatisticsRepository statistics,
            CatalogProjectionRepository projections,
            CatalogFacetRepository facetRepository) {
        this.jdbc = jdbc;
        this.statistics = statistics;
        this.projections = projections;
        this.facetRepository = facetRepository;
    }

    /**
     * Busca los elementos solicitados mediante {@code search}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     * @param publishers Valor de {@code publishers} utilizado por la operación.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @param candidates Valor de {@code candidates} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    public List<AppListItem> search(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
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
                    sort,
                    page,
                    pageSize,
                    candidates);
        }
        status = normalizeCatalogStatus(status);
        SearchRanking ranking = SearchRanking.from(query);
        StringBuilder sql = new StringBuilder("SELECT a.id");
        List<Object> params = new ArrayList<>();
        if (ranking.active()) {
            sql.append(", ").append(ranking.scoreSql()).append(" AS search_score");
            params.addAll(ranking.params());
        }
        sql.append("""

                FROM software_apps a
                WHERE a.app_status = 'active'
                """);
        CatalogFilterSql.appendAll(
                sql, params, query, status, operatingSystems, architecture, tags, publishers);
        sql.append(" ORDER BY ").append(orderBy(sort, ranking.innerPrefix()));
        sql.append(" LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        List<UUID> appIds = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                params.toArray());
        return loadPage(appIds);
    }

    /**
     * Ejecuta la operación {@code count}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     * @param publishers Valor de {@code publishers} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     * @param candidates Valor de {@code candidates} utilizado por la operación.
     * @return Número de elementos afectados por la operación.
     */
    public long count(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            SemanticCandidateSet candidates) {
        if (candidates.semantic()) {
            return semanticCount(
                    query,
                    status,
                    operatingSystems,
                    architecture,
                    tags,
                    publishers,
                    candidates);
        }
        status = normalizeCatalogStatus(status);
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*)
                FROM software_apps a
                WHERE a.app_status = 'active'
                """);
        List<Object> params = new ArrayList<>();
        CatalogFilterSql.appendAll(
                sql, params, query, status, operatingSystems, architecture, tags, publishers);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    /**
     * Calcula la primera página de cada letra sin ordenar ni materializar el catálogo completo.
     *
     * @param query Texto de búsqueda activo.
     * @param status Estado público activo.
     * @param operatingSystems Sistemas operativos activos.
     * @param architecture Arquitectura activa.
     * @param tags Tags activas.
     * @param publishers Editores activos.
     * @param pageSize Tamaño de página actual.
     * @param candidates Candidatos semánticos o degradación literal de la misma petición.
     * @return Entradas disponibles del índice alfabético.
     */
    public List<CatalogAlphabetEntry> alphabet(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            int pageSize,
            SemanticCandidateSet candidates) {
        return facetRepository.alphabet(
                query, status, operatingSystems, architecture, tags, publishers, pageSize, candidates);
    }

    /** Devuelve tags y editores compatibles con los filtros activos. */
    public CatalogFacetsResponse facets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            SemanticCandidateSet candidates) {
        return facetRepository.facets(
                query, status, operatingSystems, architecture, tags, publishers, candidates);
    }
    private List<AppListItem> semanticSearch(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            String sort,
            int page,
            int pageSize,
            SemanticCandidateSet candidates) {
        status = normalizeCatalogStatus(status);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SemanticCandidateSql.cte(query, candidates, params));
        sql.append("""
                SELECT a.id, ranked.semantic_rank
                FROM software_apps a
                JOIN semantic_candidates ranked ON ranked.id = a.id
                WHERE a.app_status = 'active'
                """);
        CatalogFilterSql.appendStructured(
                sql,
                params,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers);
        sql.append(" ORDER BY ")
                .append(orderBy(sort, "ranked.semantic_rank ASC, "))
                .append(" LIMIT ? OFFSET ?");
        params.add(pageSize);
        params.add((page - 1) * pageSize);
        List<UUID> appIds = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> UuidBytes.toUuid(rs.getBytes("id")),
                params.toArray());
        return loadPage(appIds);
    }

    /** Carga las fichas fuera de la consulta ordenada y restaura el orden de sus IDs. */
    private List<AppListItem> loadPage(List<UUID> orderedIds) {
        Map<UUID, AppListItem> itemsById = projections.listItems(orderedIds);
        return orderedIds.stream()
                .map(itemsById::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * Ejecuta la operación {@code semanticCount}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     * @param publishers Valor de {@code publishers} utilizado por la operación.
     * @param candidates Valor de {@code candidates} utilizado por la operación.
     * @return Resultado producido por {@code semanticCount}.
     */
    private long semanticCount(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            SemanticCandidateSet candidates) {
        status = normalizeCatalogStatus(status);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SemanticCandidateSql.cte(query, candidates, params));
        sql.append("""
                SELECT COUNT(*)
                FROM software_apps a
                JOIN semantic_candidates ranked ON ranked.id = a.id
                WHERE a.app_status = 'active'
                """);
        CatalogFilterSql.appendStructured(
                sql,
                params,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return count == null ? 0 : count;
    }

    /**
     * Ejecuta la operación {@code details}.
     *
     * @param publicId Identificador de {@code public} utilizado por la operación.
     * @return Resultado producido por {@code details}.
     * @throws NotFoundException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    public AppDetails details(String publicId) {
        return projections.details(publicId);
    }

    /** Devuelve proyecciones en el mismo orden lógico de los identificadores solicitados. */
    public Map<UUID, AppListItem> listItems(Collection<UUID> requestedIds) {
        return projections.listItems(requestedIds);
    }

    /** Resuelve un identificador público o interno de una aplicación activa. */
    public UUID softwareAppId(String publicId) {
        return projections.softwareAppId(publicId);
    }

    /** Resuelve exclusivamente un UUID público de una aplicación activa. */
    public UUID publicSoftwareAppId(String publicId) {
        return projections.publicSoftwareAppId(publicId);
    }
    public CatalogStatsResponse stats() {
        return statistics.stats();
    }

    /**
     * Obtiene la versión barata que invalida las respuestas públicas almacenadas localmente.
     *
     * @return Versión y contadores autoritativos de MySQL.
     */
    public String cacheVersion() {
        return statistics.cacheVersion();
    }

    /**
     * Ejecuta la operación {@code changeEvent}.
     *
     * @return Resultado producido por {@code changeEvent}.
     */
    public CatalogChangeEvent changeEvent() {
        return statistics.changeEvent();
    }

    /**
     * Ejecuta la operación {@code changeVersion}.
     *
     * @return Resultado producido por {@code changeVersion}.
     */
    public String changeVersion() {
        return statistics.changeVersion();
    }

    private String orderBy(String sort, String relevancePrefix) {
        String relevanceOrder =
                relevancePrefix == null || relevancePrefix.isBlank() ? "" : relevancePrefix;
        String selectedOrder = switch (sort) {
            case "updated" ->
                    "a.updated_at DESC, " + relevanceOrder + "a.normalized_name ASC, a.id ASC";
            case "downloads" ->
                    "a.download_count DESC, " + relevanceOrder + "a.normalized_name ASC, a.id ASC";
            default -> "a.normalized_name ASC, " + relevanceOrder + "a.id ASC";
        };
        return "name".equals(sort) ? selectedOrder : reviewLastOrder() + ", " + selectedOrder;
    }

    /**
     * Ejecuta la operación {@code reviewLastOrder}.
     *
     * @return Resultado producido por {@code reviewLastOrder}.
     */
    private String reviewLastOrder() {
        return REVIEW_LAST_ORDER;
    }

    /**
     * Normaliza el valor recibido mediante {@code normalizeSearchQuery}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code normalizeSearchQuery}.
     */
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

    /**
     * Normaliza el valor recibido mediante {@code normalizeCatalogStatus}.
     *
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @return Resultado producido por {@code normalizeCatalogStatus}.
     * @throws BadRequestException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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

    /**
     * Ejecuta la operación {@code compactSearchQuery}.
     *
     * @param normalized Valor de {@code normalized} utilizado por la operación.
     * @return Resultado producido por {@code compactSearchQuery}.
     */
    static String compactSearchQuery(String normalized) {
        return normalized == null ? "" : normalized.replaceAll("\\s+", "");
    }

    /** Agrupa una etiqueta de faceta por su primera letra visible. */
    static String facetLetter(String value) {
        return CatalogFacetRepository.facetLetter(value);
    }
    /** Elige la URL de origen pública sin exponer endpoints de artefactos directos. */
    static String originUrl(
            String winstallId,
            String officialUrl,
            String resolvedOriginUrl) {
        return CatalogProjectionRepository.originUrl(
                winstallId, officialUrl, resolvedOriginUrl);
    }
    record SearchRanking(boolean active, String scoreSql, List<Object> params) {
        /**
         * Ejecuta la operación {@code from}.
         *
         * @param query Valor de {@code query} utilizado por la operación.
         * @return Resultado producido por {@code from}.
         */
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

        /**
         * Ejecuta la operación {@code innerPrefix}.
         *
         * @return Resultado producido por {@code innerPrefix}.
         */
        String innerPrefix() {
            return active ? "search_score DESC, " : "";
        }

    }

    /**
     * Busca los elementos solicitados mediante {@code searchTokens}.
     *
     * @param normalized Valor de {@code normalized} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
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

}
