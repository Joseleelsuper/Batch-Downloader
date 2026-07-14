"""Add icon-enrichment observability to the scraper-owned schema.

Revision ID: 20260713_0008
Revises: 20260711_0007
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260713_0008"
down_revision: str | None = "20260711_0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "scraper_metric_snapshots",
        sa.Column("queued_icon_enrichment", sa.Integer(), nullable=False, server_default="0"),
    )
    op.alter_column("scraper_metric_snapshots", "queued_icon_enrichment", server_default=None)


def downgrade() -> None:
    op.drop_column("scraper_metric_snapshots", "queued_icon_enrichment")
