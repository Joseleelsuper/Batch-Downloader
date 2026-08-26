"""Define la migración de esquema `20260709_0006_descriptor_pipeline`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260709_0006"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260709_0005"
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
        "scraper_work_items",
        sa.Column("priority", sa.Integer(), server_default=sa.text("0"), nullable=False),
    )
    op.add_column(
        "scraper_metric_snapshots",
        sa.Column(
            "queued_scraper_descriptor",
            sa.Integer(),
            server_default=sa.text("0"),
            nullable=False,
        ),
    )
    op.create_table(
        "scraper_rate_limits",
        sa.Column("key", sa.String(64), nullable=False),
        sa.Column("next_allowed_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("key"),
    )
    op.execute(
        """
        INSERT INTO scraper_rate_limits (`key`, next_allowed_at, updated_at)
        VALUES ('descriptor_llm', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
        """
    )


def downgrade() -> None:
    """Ejecuta la operación `downgrade`.
    """
    op.drop_table("scraper_rate_limits")
    op.drop_column("scraper_metric_snapshots", "queued_scraper_descriptor")
    op.drop_column("scraper_work_items", "priority")
