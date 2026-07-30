"""Define la migración de esquema `20260630_0003_scraper_control`.
"""

import sqlalchemy as sa

from alembic import op

revision = "20260630_0003"
"""Estado global asociado a `revision`.
"""
down_revision = "20260627_0002"
"""Estado global asociado a `down_revision`.
"""
branch_labels = None
"""Estado global asociado a `branch_labels`.
"""
depends_on = None
"""Estado global asociado a `depends_on`.
"""


def upgrade() -> None:
    """Ejecuta la operación `upgrade`.
    """
    op.add_column("scrape_runs", sa.Column("current_package_id", sa.String(180), nullable=True))
    op.add_column("scrape_runs", sa.Column("current_app_name", sa.String(180), nullable=True))
    op.add_column("scrape_runs", sa.Column("current_phase", sa.String(80), nullable=True))
    op.add_column(
        "scrape_runs",
        sa.Column("stop_requested", sa.Boolean(), server_default=sa.text("0"), nullable=False),
    )
    op.add_column("scrape_runs", sa.Column("paused_at", sa.DateTime(), nullable=True))
    op.add_column(
        "scrape_runs",
        sa.Column("apps_skipped", sa.Integer(), server_default=sa.text("0"), nullable=False),
    )

    op.create_table(
        "scraper_commands",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("command", sa.String(32), nullable=False),
        sa.Column("status", sa.String(32), nullable=False, server_default="pending"),
        sa.Column("message", sa.String(1000), nullable=True),
        sa.Column("created_by", sa.String(180), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("consumed_at", sa.DateTime(), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_scraper_commands_status_created",
        "scraper_commands",
        ["status", "created_at"],
    )


def downgrade() -> None:
    """Ejecuta la operación `downgrade`.
    """
    op.drop_index("ix_scraper_commands_status_created", table_name="scraper_commands")
    op.drop_table("scraper_commands")
    op.drop_column("scrape_runs", "apps_skipped")
    op.drop_column("scrape_runs", "paused_at")
    op.drop_column("scrape_runs", "stop_requested")
    op.drop_column("scrape_runs", "current_phase")
    op.drop_column("scrape_runs", "current_app_name")
    op.drop_column("scrape_runs", "current_package_id")
