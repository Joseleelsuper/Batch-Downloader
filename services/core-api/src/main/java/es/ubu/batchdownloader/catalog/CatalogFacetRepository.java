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
 * Calcula facetas e índice alfabético reutilizando los filtros de resultados y el mismo conjunto de
 * candidatos semánticos.
 *
 * @see es.ubu.batchdownloader.catalog.CatalogQuery
 * @see es.ubu.batchdownloader.catalog.CatalogFilterSql
 * @see es.ubu.batchdownloader.catalog.SemanticCandidateSql
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@Repository
public class CatalogFacetRepository {
    private final JdbcTemplate jdbc;

    /**
     * Conecta el acceso SQL a las facetas y grupos del catálogo.
     *
     * @param jdbc Acceso SQL a catálogo, fuentes y proyecciones persistidas en MySQL.
     */
    public CatalogFacetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Cuenta grupos A–Z y otros prefijos bajo los filtros vigentes y calcula la página inicial de
     * cada letra con el tamaño solicitado.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param pageSize Aplicaciones por página; el controlador limita el rango a 1–100.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @return grupos con contenido; # ocupa la primera página cuando hay otros prefijos.
     */
    public List<CatalogAlphabetEntry> alphabet(CatalogQuery filters, int pageSize, SemanticCandidateSet candidates) {
        int safePageSize = Math.max(1, pageSize);
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        if (candidates.semantic()) {
            sql.append(SemanticCandidateSql.cte(filters.query(), candidates, params));
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
            CatalogFilterSql.appendStructured(sql, params, filters);
        } else {
            CatalogFilterSql.appendAll(sql, params, filters);
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
     * Consulta etiquetas y editores mediante el mismo constructor de filtros y candidatos.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @return facetas ordenadas por recuento descendente y etiqueta.
     */
    public CatalogFacetsResponse facets(CatalogQuery filters, SemanticCandidateSet candidates) {
        return new CatalogFacetsResponse(
                facetValues(filters, candidates, true), facetValues(filters, candidates, false));
    }

    /**
     * Agrupa por etiqueta normalizada o editor y cuenta aplicaciones distintas tras aplicar todos
     * los filtros, incluidos los de faceta seleccionada.
     *
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @param tags true agrupa etiquetas; false agrupa editores no vacíos.
     * @return facetas con recuentos del conjunto filtrado.
     */
    private List<FacetItem> facetValues(
            CatalogQuery filters, SemanticCandidateSet candidates, boolean tags) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        if (candidates.semantic()) {
            sql.append(SemanticCandidateSql.cte(filters.query(), candidates, params));
        }
        sql.append(tags ? """
                SELECT MIN(t.tag) AS label, t.normalized_tag AS normalized_value,
                       COUNT(DISTINCT a.id) AS app_count
                FROM software_app_tags t
                JOIN software_apps a ON a.id = t.software_app_id
                """ : """
                SELECT a.publisher AS label, LOWER(TRIM(a.publisher)) AS normalized_value,
                       COUNT(DISTINCT a.id) AS app_count
                FROM software_apps a
                """);
        if (candidates.semantic()) {
            sql.append(" JOIN semantic_candidates ranked ON ranked.id = a.id");
        }
        sql.append(" WHERE a.app_status = 'active'");
        if (!tags) {
            sql.append(" AND a.publisher IS NOT NULL AND TRIM(a.publisher) <> ''");
        }
        if (candidates.semantic()) {
            CatalogFilterSql.appendStructured(sql, params, filters);
        } else {
            CatalogFilterSql.appendAll(sql, params, filters);
        }
        sql.append(tags ? " GROUP BY t.normalized_tag" : " GROUP BY a.publisher")
                .append(" ORDER BY app_count DESC, label ASC");
        return jdbc.query(sql.toString(), (rs, rowNum) -> facetItem(
                rs.getString("label"), rs.getString("normalized_value"), rs.getLong("app_count")),
                params.toArray());
    }












    /**
     * Completa etiqueta o clave ausentes y calcula el grupo alfabético de presentación.
     *
     * @param label Texto visible de una faceta; se usa guion cuando falta o está en blanco.
     * @param normalizedValue Clave de comparación de la faceta; si falta se deriva de su etiqueta
     *     visible.
     * @param count Número de aplicaciones distintas que pertenecen a la faceta o grupo alfabético.
     * @return faceta con etiqueta no vacía y recuento recibido.
     */
    private FacetItem facetItem(String label, String normalizedValue, long count) {
        String safeLabel = label == null || label.isBlank() ? "-" : label.trim();
        String safeNormalized = normalizedValue == null || normalizedValue.isBlank()
                ? safeLabel.toLowerCase(Locale.ROOT)
                : normalizedValue.trim();
        return new FacetItem(safeLabel, safeLabel, safeNormalized, facetLetter(safeLabel), count);
    }

    /**
     * Retira diacríticos y busca el primer prefijo significativo; cifras o letras no latinas se
     * agrupan en # y la puntuación inicial se omite.
     *
     * @param value Texto que se normaliza o clasifica según el método.
     * @return letra A–Z o #.
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
