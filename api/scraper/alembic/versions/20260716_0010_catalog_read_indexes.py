"""Add covering indexes used by the Core catalog read model.

Revision ID: 20260716_0010
Revises: 20260713_0009
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260716_0010"
down_revision: str | None = "20260713_0009"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # Core orders active applications by this exact tuple. Without a covering
    # index MySQL filesorts every active row before applying LIMIT.
    op.create_index(
        "ix_software_apps_status_updated_name_id",
        "software_apps",
        ["app_status", sa.text("updated_at DESC"), "normalized_name", "id"],
    )

    # Availability is queried from several Core read paths. Materialising the
    # immutable JSON predicates once keeps the security rules unchanged while
    # avoiding JSON_EXTRACT against the large metadata column on every request.
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
    op.drop_index(
        "ix_resolved_sources_catalog_downloadable",
        table_name="resolved_sources",
    )
    op.drop_column("resolved_sources", "catalog_downloadable")
    op.drop_index(
        "ix_software_apps_status_updated_name_id",
        table_name="software_apps",
    )
