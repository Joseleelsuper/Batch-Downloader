package es.ubu.batchdownloader.catalog;

import java.util.List;

/**
 * Convierte el array de candidatos semánticos en una tabla SQL parametrizada para que MySQL aplique
 * visibilidad, filtros y paginación.
 *
 * @see es.ubu.batchdownloader.catalog.SemanticCandidateSet
 * @see es.ubu.batchdownloader.catalog.CatalogRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
final class SemanticCandidateSql {
    /**
     * Impide instancias del constructor estático de la tabla de candidatos.
     */
    private SemanticCandidateSql() {}

    /**
     * Añade el JSON como un parámetro y construye una CTE que expone UUID binario y rango sin
     * incorporar candidatos léxicos.
     *
     * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
     * @param candidates Resultado de la resolución semántica; el modo aplicado decide si se filtra
     *     por sus UUID.
     * @param params Valores enlazados en el mismo orden que los marcadores añadidos a SQL.
     * @return fragmento WITH semantic_candidates.
     * @throws IllegalArgumentException si se intenta construir búsqueda semántica sin texto.
     */
    static String cte(
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
}
