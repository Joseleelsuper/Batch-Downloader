"""initial scraper schema

Revision ID: 20260626_0001
Revises:
Create Date: 2026-06-26
"""

from alembic import op
import sqlalchemy as sa

revision = "20260626_0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "software_apps",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("winstall_id", sa.String(180), nullable=False),
        sa.Column("slug", sa.String(180), nullable=False),
        sa.Column("name", sa.String(180), nullable=False),
        sa.Column("normalized_name", sa.String(180), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("publisher", sa.String(180), nullable=True),
        sa.Column("icon_url", sa.String(2048), nullable=True),
        sa.Column("official_url", sa.String(2048), nullable=True),
        sa.Column("latest_version", sa.String(100), nullable=True),
        sa.Column("app_status", sa.String(32), nullable=False),
        sa.Column("metadata_json", sa.JSON(), nullable=True),
        sa.Column("version", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("slug"),
        sa.UniqueConstraint("winstall_id"),
    )
    op.create_index("ix_software_apps_app_status", "software_apps", ["app_status"])
    op.create_index("ix_software_apps_normalized_name", "software_apps", ["normalized_name"])
    op.create_index(
        "ix_software_apps_status_name", "software_apps", ["app_status", "normalized_name"]
    )

    op.create_table(
        "download_sources",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("software_app_id", sa.BINARY(16), nullable=False),
        sa.Column("operating_system", sa.String(32), nullable=False),
        sa.Column("architecture", sa.String(32), nullable=False),
        sa.Column("initial_url", sa.String(2048), nullable=True),
        sa.Column("resolver_type", sa.String(50), nullable=False),
        sa.Column("resolver_config", sa.JSON(), nullable=True),
        sa.Column("resolution_status", sa.String(32), nullable=False),
        sa.Column("validation_status", sa.String(32), nullable=False),
        sa.Column("version", sa.BigInteger(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["software_app_id"], ["software_apps.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_download_sources_app_platform",
        "download_sources",
        ["software_app_id", "operating_system", "architecture", "resolution_status"],
    )
    op.create_index("ix_download_sources_resolution_status", "download_sources", ["resolution_status"])
    op.create_index("ix_download_sources_validation_status", "download_sources", ["validation_status"])

    op.create_table(
        "source_allowed_domains",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("source_id", sa.BINARY(16), nullable=False),
        sa.Column("domain", sa.String(253), nullable=False),
        sa.Column("include_subdomains", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["source_id"], ["download_sources.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("source_id", "domain", name="uq_source_allowed_domain"),
    )

    op.create_table(
        "resolved_sources",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("download_source_id", sa.BINARY(16), nullable=False),
        sa.Column("resolved_url_encrypted", sa.Text(), nullable=False),
        sa.Column("final_domain", sa.String(253), nullable=False),
        sa.Column("filename", sa.String(255), nullable=True),
        sa.Column("extension", sa.String(20), nullable=True),
        sa.Column("content_type", sa.String(180), nullable=True),
        sa.Column("size_bytes", sa.BigInteger(), nullable=True),
        sa.Column("version", sa.String(100), nullable=True),
        sa.Column("score", sa.Integer(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("validation_status", sa.String(32), nullable=False),
        sa.Column("checked_at", sa.DateTime(), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("metadata_json", sa.JSON(), nullable=True),
        sa.ForeignKeyConstraint(["download_source_id"], ["download_sources.id"]),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_resolved_sources_source_expiry",
        "resolved_sources",
        ["download_source_id", "expires_at"],
    )
    op.create_index(
        "ix_resolved_sources_status_expiry", "resolved_sources", ["status", "expires_at"]
    )
    op.create_index("ix_resolved_sources_status", "resolved_sources", ["status"])
    op.create_index(
        "ix_resolved_sources_validation_status", "resolved_sources", ["validation_status"]
    )

    op.create_table(
        "resolver_logs",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("download_source_id", sa.BINARY(16), nullable=True),
        sa.Column("phase", sa.String(50), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("message", sa.String(2000), nullable=True),
        sa.Column("safe_metadata", sa.JSON(), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["download_source_id"], ["download_sources.id"]),
        sa.PrimaryKeyConstraint("id"),
    )

    op.create_table(
        "scrape_runs",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        sa.Column("started_at", sa.DateTime(), nullable=False),
        sa.Column("heartbeat_at", sa.DateTime(), nullable=False),
        sa.Column("finished_at", sa.DateTime(), nullable=True),
        sa.Column("apps_discovered", sa.Integer(), nullable=False),
        sa.Column("apps_resolved", sa.Integer(), nullable=False),
        sa.Column("apps_failed", sa.Integer(), nullable=False),
        sa.Column("worker_id", sa.String(120), nullable=False),
        sa.Column("error_summary", sa.String(1000), nullable=True),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index("ix_scrape_runs_status", "scrape_runs", ["status"])


def downgrade() -> None:
    op.drop_index("ix_scrape_runs_status", table_name="scrape_runs")
    op.drop_table("scrape_runs")
    op.drop_table("resolver_logs")
    op.drop_index("ix_resolved_sources_validation_status", table_name="resolved_sources")
    op.drop_index("ix_resolved_sources_status", table_name="resolved_sources")
    op.drop_index("ix_resolved_sources_status_expiry", table_name="resolved_sources")
    op.drop_index("ix_resolved_sources_source_expiry", table_name="resolved_sources")
    op.drop_table("resolved_sources")
    op.drop_table("source_allowed_domains")
    op.drop_index("ix_download_sources_validation_status", table_name="download_sources")
    op.drop_index("ix_download_sources_resolution_status", table_name="download_sources")
    op.drop_index("ix_download_sources_app_platform", table_name="download_sources")
    op.drop_table("download_sources")
    op.drop_index("ix_software_apps_status_name", table_name="software_apps")
    op.drop_index("ix_software_apps_normalized_name", table_name="software_apps")
    op.drop_index("ix_software_apps_app_status", table_name="software_apps")
    op.drop_table("software_apps")
