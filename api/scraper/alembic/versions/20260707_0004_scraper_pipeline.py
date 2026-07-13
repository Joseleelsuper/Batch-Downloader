"""add scraper pipeline queues and installer metadata

Revision ID: 20260707_0004
Revises: 20260630_0003
Create Date: 2026-07-07
"""

import sqlalchemy as sa

from alembic import op

revision = "20260707_0004"
down_revision = "20260630_0003"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("resolved_sources", sa.Column("release_rank", sa.Integer(), nullable=True))
    op.add_column(
        "resolved_sources",
        sa.Column("is_latest", sa.Boolean(), server_default=sa.text("0"), nullable=False),
    )
    op.add_column("resolved_sources", sa.Column("version_status", sa.String(32), nullable=True))
    op.create_index("ix_resolved_sources_latest", "resolved_sources", ["is_latest"])

    op.create_table(
        "scraper_work_items",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("run_id", sa.BINARY(16), nullable=True),
        sa.Column("queue", sa.String(32), nullable=False),
        sa.Column("status", sa.String(32), server_default="queued", nullable=False),
        sa.Column("package_id", sa.String(180), nullable=False),
        sa.Column("app_name", sa.String(180), nullable=True),
        sa.Column("payload_json", sa.JSON(), nullable=True),
        sa.Column("attempts", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("lease_owner", sa.String(120), nullable=True),
        sa.Column("lease_expires_at", sa.DateTime(), nullable=True),
        sa.Column("available_at", sa.DateTime(), nullable=False),
        sa.Column("last_error", sa.String(1000), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["run_id"], ["scrape_runs.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("queue", "package_id", name="uq_scraper_work_queue_package"),
    )
    op.create_index(
        "ix_scraper_work_queue_status_available",
        "scraper_work_items",
        ["queue", "status", "available_at"],
    )
    op.create_index("ix_scraper_work_lease", "scraper_work_items", ["status", "lease_expires_at"])

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
    op.create_index("ix_scraper_snapshots_expires", "scraper_worker_snapshots", ["expires_at"])

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
        sa.Column("captured_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["run_id"], ["scrape_runs.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_scraper_metric_snapshots_captured",
        "scraper_metric_snapshots",
        ["captured_at"],
    )


def downgrade() -> None:
    op.drop_index("ix_scraper_metric_snapshots_captured", table_name="scraper_metric_snapshots")
    op.drop_table("scraper_metric_snapshots")
    op.drop_index("ix_scraper_snapshots_expires", table_name="scraper_worker_snapshots")
    op.drop_index("ix_scraper_snapshots_stage_captured", table_name="scraper_worker_snapshots")
    op.drop_table("scraper_worker_snapshots")
    op.drop_index("ix_scraper_work_lease", table_name="scraper_work_items")
    op.drop_index("ix_scraper_work_queue_status_available", table_name="scraper_work_items")
    op.drop_table("scraper_work_items")
    op.drop_index("ix_resolved_sources_latest", table_name="resolved_sources")
    op.drop_column("resolved_sources", "version_status")
    op.drop_column("resolved_sources", "is_latest")
    op.drop_column("resolved_sources", "release_rank")
