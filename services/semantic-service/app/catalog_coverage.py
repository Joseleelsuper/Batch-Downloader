"""Calcula la cobertura del índice sobre el catálogo activo."""

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
    """Devuelve documentos activos, vectores coincidentes y la huella del catálogo."""
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
