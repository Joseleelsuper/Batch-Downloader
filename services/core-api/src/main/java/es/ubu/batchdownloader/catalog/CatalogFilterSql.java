package es.ubu.batchdownloader.catalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Construye filtros SQL parametrizados compartidos por resultados, totales, facetas e índice
 * alfabético del catálogo.
 *
 * @see es.ubu.batchdownloader.catalog.CatalogQuery
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
final class CatalogFilterSql {
    /**
     * Impide instancias del constructor estático de filtros SQL.
     */
    private CatalogFilterSql() {}

    /**
     * Añade filtro textual y filtros estructurados manteniendo el orden de sus parámetros.
     *
     * @param sql Sentencia en construcción, formada solo por fragmentos constantes y marcadores de
     *     parámetros.
     * @param params Valores enlazados en el mismo orden que los marcadores añadidos a SQL.
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     */
    static void appendAll(StringBuilder sql, List<Object> params, CatalogQuery filters) {
        appendLexical(sql, params, filters.query());
        appendStructured(sql, params, filters);
    }

    /**
     * Busca contenido de nombre, editor, descripciones, identificador y etiquetas con variantes
     * normalizadas y sin espacios cuando corresponda.
     *
     * @param sql Sentencia en construcción, formada solo por fragmentos constantes y marcadores de
     *     parámetros.
     * @param params Valores enlazados en el mismo orden que los marcadores añadidos a SQL.
     * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
     */
    private static void appendLexical(
            StringBuilder sql,
            List<Object> params,
            String query) {
        if (query == null || query.isBlank()) {
            return;
        }
        String normalized = CatalogRepository.normalizeSearchQuery(query);
        String normalizedLike = "%" + normalized + "%";
        String compactLike = "%" + CatalogRepository.compactSearchQuery(normalized) + "%";
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

    /**
     * Aplica estado, plataformas y arquitectura, cualquier editor solicitado y todas las etiquetas
     * normalizadas distintas.
     *
     * @param sql Sentencia en construcción, formada solo por fragmentos constantes y marcadores de
     *     parámetros.
     * @param params Valores enlazados en el mismo orden que los marcadores añadidos a SQL.
     * @param filters Texto, estado, plataformas, arquitectura, etiquetas y editores de una misma
     *     búsqueda.
     */
    static void appendStructured(StringBuilder sql, List<Object> params, CatalogQuery filters) {
        appendSource(sql, params, filters.status(), filters.operatingSystems(), filters.architecture());
        List<String> normalizedPublishers = normalizedDistinct(filters.publishers());
        if (!normalizedPublishers.isEmpty()) {
            sql.append(" AND LOWER(TRIM(COALESCE(a.publisher, ''))) IN (");
            CatalogSql.appendPlaceholders(sql, normalizedPublishers.size());
            sql.append(')');
            params.addAll(normalizedPublishers);
        }

        List<String> normalizedTags = normalizedDistinct(filters.tags());
        if (!normalizedTags.isEmpty()) {
            sql.append(" AND (SELECT COUNT(DISTINCT t.normalized_tag) "
                    + "FROM software_app_tags t WHERE t.software_app_id = a.id "
                    + "AND t.normalized_tag IN (");
            CatalogSql.appendPlaceholders(sql, normalizedTags.size());
            sql.append(")) >= ?\n");
            params.addAll(normalizedTags);
            params.add(normalizedTags.size());
        }
    }

    /**
     * Filtra la proyección de plataformas con OR y el estado de catálogo; unresolved agrupa review
     * y missing y la arquitectura exige una fuente coincidente.
     *
     * @param sql Sentencia en construcción, formada solo por fragmentos constantes y marcadores de
     *     parámetros.
     * @param params Valores enlazados en el mismo orden que los marcadores añadidos a SQL.
     * @param status Estado del catálogo; all no filtra y available, review o missing seleccionan su
     *     estado público.
     * @param operatingSystems Plataformas con semántica OR; una lista vacía representa todas las
     *     plataformas.
     * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
     *     aplicación.
     */
    private static void appendSource(
            StringBuilder sql,
            List<Object> params,
            String status,
            List<String> operatingSystems,
            String architecture) {
        if (operatingSystems != null && !operatingSystems.isEmpty()) {
            sql.append(" AND (");
            for (int index = 0; index < operatingSystems.size(); index++) {
                if (index > 0) {
                    sql.append(" OR ");
                }
                sql.append("JSON_CONTAINS(COALESCE(a.operating_systems_json, JSON_ARRAY()), "
                        + "JSON_QUOTE(?))");
                params.add(operatingSystems.get(index));
            }
            sql.append(')');
        }
        if (!"all".equals(status)) {
            if ("unresolved".equals(status)) {
                sql.append(" AND a.catalog_status IN ('review', 'missing')");
            } else {
                sql.append(" AND a.catalog_status = ?");
                params.add(status);
            }
        }
        if (architecture != null && !architecture.isBlank()) {
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

    /**
     * Recorta, convierte a minúsculas y deduplica valores no blancos con orden de primera
     * aparición.
     *
     * @param values Textos que se recortan, normalizan y deduplican conservando su primera
     *     aparición.
     * @return valores normalizados o lista vacía.
     */
    private static List<String> normalizedDistinct(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.toLowerCase(Locale.ROOT).trim());
            }
        }
        return List.copyOf(normalized);
    }
}
