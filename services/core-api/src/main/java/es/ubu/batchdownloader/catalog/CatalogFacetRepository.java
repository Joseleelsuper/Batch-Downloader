package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogAlphabetEntry;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogFacetsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.FacetItem;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Resuelve el índice alfabético y las facetas compatibles con la selección actual.
 */
@Repository
public class CatalogFacetRepository {
    private final JdbcTemplate jdbc;

    /** Inicializa las facetas con el acceso JDBC compartido. */
    public CatalogFacetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CatalogAlphabetEntry> alphabet(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            int pageSize,
            SemanticCandidateSet candidates) {
        status = CatalogRepository.normalizeCatalogStatus(status);
        int safePageSize = Math.max(1, pageSize);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        if (candidates.semantic()) {
            sql.append(SemanticCandidateSql.cte(query, candidates, params));
        }
        sql.append("SELECT COALESCE(SUM(UPPER(LEFT(TRIM(a.normalized_name), 1)) "
                + "NOT REGEXP '^[A-Z]$'), 0) AS count_other");
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            String alias = Character.toString(Character.toLowerCase(letter));
            sql.append(", COALESCE(SUM(a.normalized_name < ?), 0) AS before_")
                    .append(alias)
                    .append(", COALESCE(SUM(UPPER(LEFT(TRIM(a.normalized_name), 1)) = ?), 0) AS count_")
                    .append(alias);
            params.add(alias);
            params.add(Character.toString(letter));
        }
        sql.append(" FROM software_apps a");
        if (candidates.semantic()) {
            sql.append(" JOIN semantic_candidates ranked ON ranked.id = a.id");
        }
        sql.append(" WHERE a.app_status = 'active'");
        if (candidates.semantic()) {
            CatalogFilterSql.appendStructured(
                    sql, params, status, operatingSystems, architecture, tags, publishers);
        } else {
            CatalogFilterSql.appendAll(
                    sql, params, query, status, operatingSystems, architecture, tags, publishers);
        }
        List<List<CatalogAlphabetEntry>> rows = jdbc.query(
                sql.toString(),
                (rs, rowNum) -> {
                    List<CatalogAlphabetEntry> entries = new ArrayList<>();
                    long otherCount = rs.getLong("count_other");
                    if (otherCount > 0) {
                        entries.add(new CatalogAlphabetEntry("#", 1, otherCount));
                    }
                    for (char letter = 'A'; letter <= 'Z'; letter++) {
                        String alias = Character.toString(Character.toLowerCase(letter));
                        long count = rs.getLong("count_" + alias);
                        if (count < 1) {
                            continue;
                        }
                        long preceding = rs.getLong("before_" + alias);
                        int page = Math.toIntExact((preceding / safePageSize) + 1);
                        entries.add(new CatalogAlphabetEntry(Character.toString(letter), page, count));
                    }
                    return List.copyOf(entries);
                },
                params.toArray());
        return rows.isEmpty() ? List.of() : rows.getFirst();
    }

    /**
     * Ejecuta la operación {@code facets}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     * @param publishers Valor de {@code publishers} utilizado por la operación.
     * @return Resultado producido por {@code facets}.
     * @param candidates Valor de {@code candidates} utilizado por la operación.
     * @return Resultado producido por {@code facets}.
     */
    public CatalogFacetsResponse facets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            SemanticCandidateSet candidates) {
        if (candidates.semantic()) {
            return semanticFacets(
                    query,
                    status,
                    operatingSystems,
                    architecture,
                    tags,
                    publishers,
                    candidates);
        }
        status = CatalogRepository.normalizeCatalogStatus(status);
        return new CatalogFacetsResponse(
                tagFacets(query, status, operatingSystems, architecture, tags, publishers),
                publisherFacets(query, status, operatingSystems, architecture, tags, publishers));
    }

    /**
     * Ejecuta la operación {@code semanticSearch}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     * @param publishers Valor de {@code publishers} utilizado por la operación.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @param candidates Valor de {@code candidates} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private CatalogFacetsResponse semanticFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            SemanticCandidateSet candidates) {
        status = CatalogRepository.normalizeCatalogStatus(status);
        return new CatalogFacetsResponse(
                semanticTagFacets(
                        query,
                        status,
                        operatingSystems,
                        architecture,
                        tags,
                        publishers,
                        candidates),
                semanticPublisherFacets(
                        query,
                        status,
                        operatingSystems,
                        architecture,
                        tags,
                        publishers,
                        candidates));
    }

    /**
     * Ejecuta la operación {@code semanticTagFacets}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param publishers Valor de {@code publishers} utilizado por la operación.
     * @param candidates Valor de {@code candidates} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<FacetItem> semanticTagFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            SemanticCandidateSet candidates) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SemanticCandidateSql.cte(query, candidates, params));
        sql.append("""
                SELECT MIN(t.tag) AS label, t.normalized_tag AS normalized_value,
                       COUNT(DISTINCT a.id) AS app_count
                FROM software_app_tags t
                JOIN software_apps a ON a.id = t.software_app_id
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

    /**
     * Ejecuta la operación {@code semanticPublisherFacets}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     * @param candidates Valor de {@code candidates} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<FacetItem> semanticPublisherFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers,
            SemanticCandidateSet candidates) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SemanticCandidateSql.cte(query, candidates, params));
        sql.append("""
                SELECT a.publisher AS label, LOWER(TRIM(a.publisher)) AS normalized_value,
                       COUNT(DISTINCT a.id) AS app_count
                FROM software_apps a
                JOIN semantic_candidates ranked ON ranked.id = a.id
                WHERE a.app_status = 'active'
                  AND a.publisher IS NOT NULL
                  AND TRIM(a.publisher) <> ''
                """);
        CatalogFilterSql.appendStructured(
                sql,
                params,
                status,
                operatingSystems,
                architecture,
                tags,
                publishers);
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

    /**
     * Ejecuta la operación {@code tagFacets}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param publishers Valor de {@code publishers} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<FacetItem> tagFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers) {
        StringBuilder sql = new StringBuilder("""
                SELECT MIN(t.tag) AS label, t.normalized_tag AS normalized_value, COUNT(DISTINCT a.id) AS app_count
                FROM software_app_tags t
                JOIN software_apps a ON a.id = t.software_app_id
                WHERE a.app_status = 'active'
                """);
        List<Object> params = new ArrayList<>();
        CatalogFilterSql.appendAll(
                sql, params, query, status, operatingSystems, architecture, tags, publishers);
        sql.append("""
                GROUP BY t.normalized_tag
                ORDER BY app_count DESC, label ASC
                """);
        return jdbc.query(sql.toString(), (rs, rowNum) -> facetItem(
                rs.getString("label"),
                rs.getString("normalized_value"),
                rs.getLong("app_count")), params.toArray());
    }

    /**
     * Publica el contenido solicitado mediante {@code publisherFacets}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystems Valor de {@code operatingSystems} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param tags Valor de {@code tags} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    private List<FacetItem> publisherFacets(
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.publisher AS label, LOWER(TRIM(a.publisher)) AS normalized_value, COUNT(DISTINCT a.id) AS app_count
                FROM software_apps a
                WHERE a.app_status = 'active'
                  AND a.publisher IS NOT NULL
                  AND TRIM(a.publisher) <> ''
                """);
        List<Object> params = new ArrayList<>();
        CatalogFilterSql.appendAll(
                sql, params, query, status, operatingSystems, architecture, tags, publishers);
        sql.append("""
                GROUP BY a.publisher
                ORDER BY app_count DESC, label ASC
                """);
        return jdbc.query(sql.toString(), (rs, rowNum) -> facetItem(
                rs.getString("label"),
                rs.getString("normalized_value"),
                rs.getLong("app_count")), params.toArray());
    }

    /** Construye una faceta normalizada y su agrupación alfabética. */
    private FacetItem facetItem(String label, String normalizedValue, long count) {
        String safeLabel = label == null || label.isBlank() ? "-" : label.trim();
        String safeNormalized = normalizedValue == null || normalizedValue.isBlank()
                ? safeLabel.toLowerCase(Locale.ROOT)
                : normalizedValue.trim();
        return new FacetItem(safeLabel, safeLabel, safeNormalized, facetLetter(safeLabel), count);
    }

    /**
     * Ejecuta la operación {@code facetLetter}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code facetLetter}.
     */
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
}
