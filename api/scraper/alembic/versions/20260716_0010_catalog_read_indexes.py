"""Define la migración de esquema `20260716_0010_catalog_read_indexes`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260716_0010"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260713_0009"
"""Estado global asociado a `down_revision`.
"""
branch_labels: str | Sequence[str] | None = None
"""Estado global asociado a `branch_labels`.
"""
depends_on: str | Sequence[str] | None = None
"""Estado global asociado a `depends_on`.
"""


def upgrade() -> None:
    # Core ordena las aplicaciones activas por esta tupla exacta. Sin un índice
    # de cobertura, MySQL ordena todas las filas activas antes de aplicar LIMIT.
    """Ejecuta la operación `upgrade`.
    """
    op.create_index(
        "ix_software_apps_status_updated_name_id",
        "software_apps",
        ["app_status", sa.text("updated_at DESC"), "normalized_name", "id"],
    )

    # La disponibilidad se consulta desde varias rutas de lectura de Core. Materializar
    # una vez los predicados JSON inmutables mantiene las reglas de seguridad y evita
    # ejecutar JSON_EXTRACT sobre la columna grande de metadatos en cada solicitud.
    op.execute(
        sa.text(
            """
            ALTER TABLE resolved_sources
                ADD COLUMN catalog_downloadable TINYINT(1)
                    GENERATED ALWAYS AS (
                        CASE
                            WHEN validation_status = 'valid'
                             AND status IN ('direct', 'fallback')
                             AND COALESCE(
                                    JSON_UNQUOTE(JSON_EXTRACT(
                                        metadata_json,
                                        '$.validation_confidence'
                                    )),
                                    ''
                                 ) IN ('', 'validated', 'verified')
                             AND COALESCE(
                                    JSON_UNQUOTE(JSON_EXTRACT(
                                        metadata_json,
                                        '$.transport_security'
                                    )),
                                    ''
                                 ) NOT IN (
                                    'https_winstall_edge_attested',
                                    'http_winstall_verified'
                                 )
                            THEN 1 ELSE 0
                        END
                    ) STORED,
                ADD INDEX ix_resolved_sources_catalog_downloadable (
                    download_source_id,
                    catalog_downloadable,
                    checked_at
                )
            """
        )
    )


def downgrade() -> None:
    """Ejecuta la operación `downgrade`.
    """
    op.drop_index(
        "ix_resolved_sources_catalog_downloadable",
        table_name="resolved_sources",
    )
    op.drop_column("resolved_sources", "catalog_downloadable")
    op.drop_index(
        "ix_software_apps_status_updated_name_id",
        table_name="software_apps",
    )
