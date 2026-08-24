package es.ubu.batchdownloader.catalog;

import java.util.List;

/** Construye la tabla común ordenada de candidatos devuelta por Semantic. */
final class SemanticCandidateSql {
    private SemanticCandidateSql() {}

    /** Añade el JSON validado a los parámetros y devuelve su CTE parametrizada. */
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
