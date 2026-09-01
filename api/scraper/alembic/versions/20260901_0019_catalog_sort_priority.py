"""Materializa el orden de revisión para paginar el catálogo mediante índices."""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260901_0019"
down_revision: str | None = "20260823_0018"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Evita ordenar en memoria todas las aplicaciones antes de aplicar ``LIMIT``."""
    op.execute(
        sa.text(
            """
            ALTER TABLE software_apps
                ADD COLUMN catalog_review_priority TINYINT(1)
                    GENERATED ALWAYS AS (
                        CASE WHEN catalog_status = 'review' THEN 1 ELSE 0 END
                    ) STORED,
                ADD INDEX ix_software_apps_catalog_review_updated (
                    app_status,
                    catalog_review_priority,
                    updated_at DESC,
                    normalized_name,
                    id
                )
            """
        )
    )


def downgrade() -> None:
    """Retira los accesos ordenados y su proyección derivada."""
    op.drop_index(
        "ix_software_apps_catalog_review_updated",
        table_name="software_apps",
    )
    op.drop_column("software_apps", "catalog_review_priority")
