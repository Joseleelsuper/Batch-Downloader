"""Define la migración de esquema `20260713_0008_content_enrichment`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260713_0008"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260711_0007"
"""Estado global asociado a `down_revision`.
"""
branch_labels: str | Sequence[str] | None = None
"""Estado global asociado a `branch_labels`.
"""
depends_on: str | Sequence[str] | None = None
"""Estado global asociado a `depends_on`.
"""


def upgrade() -> None:
    """Ejecuta la operación `upgrade`.
    """
    op.add_column(
        "scraper_metric_snapshots",
        sa.Column("queued_icon_enrichment", sa.Integer(), nullable=False, server_default="0"),
    )
    op.alter_column("scraper_metric_snapshots", "queued_icon_enrichment", server_default=None)


def downgrade() -> None:
    """Ejecuta la operación `downgrade`.
    """
    op.drop_column("scraper_metric_snapshots", "queued_icon_enrichment")
