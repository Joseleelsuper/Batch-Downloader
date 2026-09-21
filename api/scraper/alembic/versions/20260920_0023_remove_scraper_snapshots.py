"""Retira las métricas históricas y las capturas HTML del panel del scraper."""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260920_0023"
down_revision: str | None = "20260914_0022"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Elimina las dos tablas transitorias que ya no tienen productores ni consumidores."""
    op.drop_table("scraper_worker_snapshots")
    op.drop_table("scraper_metric_snapshots")


def downgrade() -> None:
    """Restaura el esquema vacío; los datos transitorios eliminados no son recuperables."""
    op.create_table(
        "scraper_metric_snapshots",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("run_id", sa.BINARY(16), nullable=True),
        sa.Column("available", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("review", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("unavailable", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column(
            "queued_searcher_filter",
            sa.Integer(),
            server_default=sa.text("0"),
            nullable=False,
        ),
        sa.Column(
            "queued_filter_scraper",
            sa.Integer(),
            server_default=sa.text("0"),
            nullable=False,
        ),
        sa.Column(
            "queued_scraper_so_filter",
            sa.Integer(),
            server_default=sa.text("0"),
            nullable=False,
        ),
        sa.Column(
            "queued_so_filter_descriptor",
            sa.Integer(),
            server_default=sa.text("0"),
            nullable=False,
        ),
        sa.Column("captured_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["run_id"], ["scrape_runs.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_scraper_metric_snapshots_captured",
        "scraper_metric_snapshots",
        ["captured_at"],
    )

    op.create_table(
        "scraper_worker_snapshots",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("run_id", sa.BINARY(16), nullable=True),
        sa.Column("worker_id", sa.String(120), nullable=False),
        sa.Column("stage", sa.String(32), nullable=False),
        sa.Column("package_id", sa.String(180), nullable=True),
        sa.Column("app_name", sa.String(180), nullable=True),
        sa.Column("url", sa.String(2048), nullable=True),
        sa.Column("html", sa.Text(), nullable=True),
        sa.Column("captured_at", sa.DateTime(), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["run_id"], ["scrape_runs.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_scraper_snapshots_stage_captured",
        "scraper_worker_snapshots",
        ["stage", "captured_at"],
    )
    op.create_index(
        "ix_scraper_snapshots_expires",
        "scraper_worker_snapshots",
        ["expires_at"],
    )
