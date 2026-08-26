package es.ubu.batchdownloader.catalog;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Construye filtros SQL parametrizados compartidos por búsquedas, conteos y facetas. */
final class CatalogFilterSql {
    private CatalogFilterSql() {}

    /** Añade filtros textuales y estructurados a una consulta con alias {@code a}. */
    static void appendAll(
            StringBuilder sql,
            List<Object> params,
            String query,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers) {
        appendLexical(sql, params, query);
        appendStructured(
                sql, params, status, operatingSystems, architecture, tags, publishers);
    }

    /** Añade la búsqueda literal normalizada sin interpolar valores del usuario. */
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

    /** Añade estado, plataformas, arquitectura, tags y editores normalizados. */
    static void appendStructured(
            StringBuilder sql,
            List<Object> params,
            String status,
            List<String> operatingSystems,
            String architecture,
            List<String> tags,
            List<String> publishers) {
        appendSource(sql, params, status, operatingSystems, architecture);
        List<String> normalizedPublishers = normalizedDistinct(publishers);
        if (!normalizedPublishers.isEmpty()) {
            sql.append(" AND LOWER(TRIM(COALESCE(a.publisher, ''))) IN (");
            CatalogSql.appendPlaceholders(sql, normalizedPublishers.size());
            sql.append(')');
            params.addAll(normalizedPublishers);
        }

        List<String> normalizedTags = normalizedDistinct(tags);
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
