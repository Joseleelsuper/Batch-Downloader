"""Huella de documentos activos para comparar índices y benchmarks en la misma transacción."""

from typing import Any

CATALOG_SNAPSHOT_CTE = """
WITH catalog AS (
    SELECT encode(
        digest(
            COALESCE(
                string_agg(
                    app_id::text || ':' || content_hash,
                    '|' ORDER BY app_id
                ) FILTER (WHERE active),
                ''
            ),
            'sha256'
        ),
        'hex'
    ) AS snapshot_hash
    FROM semantic_documents
)
"""


def model_catalog_coverage(connection: Any, model_version: str) -> dict[str, Any]:
    """Obtiene cobertura y huella del catálogo con la misma vista transaccional.

    Args:
        connection: Conexión del llamador; la consulta no abre ni confirma otra transacción.
        model_version: Modelo cuyos vectores deben coincidir con el hash de cada documento.

    Returns:
        expected e indexed cuentan documentos activos; snapshot_hash identifica su contenido
        en orden de app_id, incluso para un catálogo vacío.

    See Also:
        app.store.SemanticStore.coverage_and_promote: Actualiza el estado de construcción.
        app.store.SemanticStore.activate_complete_model: Exige cobertura completa antes de activar.
    """
    return dict(connection.execute(
        CATALOG_SNAPSHOT_CTE + """
        SELECT COUNT(*) FILTER (WHERE d.active) AS expected,
               COUNT(*) FILTER (WHERE d.active AND e.content_hash = d.content_hash) AS indexed,
               (SELECT snapshot_hash FROM catalog) AS snapshot_hash
        FROM semantic_documents d
        LEFT JOIN software_embeddings e
          ON e.app_id = d.app_id AND e.model_version = %s
        """,
        (model_version,),
    ).fetchone())
