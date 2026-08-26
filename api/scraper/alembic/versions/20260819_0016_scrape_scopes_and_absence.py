"""Añade solicitudes durables, huellas de Winstall y evidencia de ausencia."""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260819_0016"
down_revision: str | None = "20260728_0015"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Aplica los contratos de refresco sin recalcular la proyección pública."""
    op.add_column("software_apps", sa.Column("winstall_latest_version", sa.String(100)))
    op.add_column("software_apps", sa.Column("winstall_updated_at", sa.DateTime()))
    op.add_column(
        "software_apps", sa.Column("winstall_summary_fingerprint", sa.String(64))
    )
    op.add_column(
        "software_apps", sa.Column("winstall_detail_fingerprint", sa.String(64))
    )
    op.create_index(
        "ix_software_apps_winstall_summary_fingerprint",
        "software_apps",
        ["winstall_summary_fingerprint"],
    )
    op.create_index(
        "ix_software_apps_winstall_detail_fingerprint",
        "software_apps",
        ["winstall_detail_fingerprint"],
    )

    op.add_column("resolved_sources", sa.Column("artifact_fingerprint", sa.String(64)))
    op.create_index(
        "ix_resolved_sources_artifact_fingerprint",
        "resolved_sources",
        ["artifact_fingerprint"],
    )

    op.add_column(
        "scrape_runs",
        sa.Column("scope", sa.String(32), nullable=False, server_default="incremental"),
    )
    op.add_column("scrape_runs", sa.Column("request_id", sa.BINARY(16)))
    op.add_column(
        "scrape_runs",
        sa.Column("target_count", sa.Integer(), nullable=False, server_default="0"),
    )
    op.add_column("scrape_runs", sa.Column("target_app_ids_json", sa.JSON()))
    op.add_column("scrape_runs", sa.Column("target_winstall_ids_json", sa.JSON()))
    for name in (
        "apps_confirmed_missing",
        "apps_needs_review",
        "apps_transient_failed",
        "apps_skipped_unchanged",
    ):
        op.add_column(
            "scrape_runs",
            sa.Column(name, sa.Integer(), nullable=False, server_default="0"),
        )
    op.create_index("ix_scrape_runs_scope", "scrape_runs", ["scope"])
    op.create_index("ix_scrape_runs_request_id", "scrape_runs", ["request_id"])

    op.add_column("scraper_commands", sa.Column("scope", sa.String(32)))
    op.add_column("scraper_commands", sa.Column("app_ids_json", sa.JSON()))
    op.add_column("scraper_commands", sa.Column("run_id", sa.BINARY(16)))
    op.add_column("scraper_commands", sa.Column("started_at", sa.DateTime()))
    op.create_index("ix_scraper_commands_scope", "scraper_commands", ["scope"])
    op.create_index("ix_scraper_commands_run_id", "scraper_commands", ["run_id"])

    op.create_table(
        "installer_absence_verifications",
        sa.Column("id", sa.BINARY(16), primary_key=True),
        sa.Column("software_app_id", sa.BINARY(16), nullable=False),
        sa.Column("status", sa.String(32), nullable=False, server_default="active"),
        sa.Column("reason_code", sa.String(80), nullable=False),
        sa.Column("notes", sa.String(2000)),
        sa.Column("checked_urls_json", sa.JSON(), nullable=False),
        sa.Column("evidence_json", sa.JSON()),
        sa.Column("verified_by", sa.String(180), nullable=False),
        sa.Column("verified_at", sa.DateTime(), nullable=False),
        sa.Column("app_version", sa.BigInteger(), nullable=False),
        sa.Column("winstall_latest_version", sa.String(100)),
        sa.Column("winstall_summary_fingerprint", sa.String(64)),
        sa.Column("winstall_detail_fingerprint", sa.String(64)),
        sa.Column("official_url_fingerprint", sa.String(64)),
        sa.Column("invalidated_at", sa.DateTime()),
        sa.Column("invalidation_reason", sa.String(180)),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(
            ["software_app_id"], ["software_apps.id"], ondelete="CASCADE"
        ),
    )
    op.create_index(
        "ix_installer_absence_verifications_status",
        "installer_absence_verifications",
        ["status"],
    )
    op.create_index(
        "ix_installer_absence_verifications_app_status",
        "installer_absence_verifications",
        ["software_app_id", "status", "verified_at"],
    )

    op.execute(
        "UPDATE software_apps SET "
        "winstall_latest_version = latest_version, "
        "winstall_updated_at = updated_at "
        "WHERE winstall_latest_version IS NULL"
    )


def downgrade() -> None:
    """Retira únicamente las estructuras añadidas por esta revisión."""
    op.drop_table("installer_absence_verifications")
    op.drop_index("ix_scraper_commands_run_id", table_name="scraper_commands")
    op.drop_index("ix_scraper_commands_scope", table_name="scraper_commands")
    op.drop_column("scraper_commands", "started_at")
    op.drop_column("scraper_commands", "run_id")
    op.drop_column("scraper_commands", "app_ids_json")
    op.drop_column("scraper_commands", "scope")

    op.drop_index("ix_scrape_runs_request_id", table_name="scrape_runs")
    op.drop_index("ix_scrape_runs_scope", table_name="scrape_runs")
    for name in (
        "apps_skipped_unchanged",
        "apps_transient_failed",
        "apps_needs_review",
        "apps_confirmed_missing",
    ):
        op.drop_column("scrape_runs", name)
    op.drop_column("scrape_runs", "target_winstall_ids_json")
    op.drop_column("scrape_runs", "target_app_ids_json")
    op.drop_column("scrape_runs", "target_count")
    op.drop_column("scrape_runs", "request_id")
    op.drop_column("scrape_runs", "scope")

    op.drop_index("ix_resolved_sources_artifact_fingerprint", table_name="resolved_sources")
    op.drop_column("resolved_sources", "artifact_fingerprint")
    op.drop_index(
        "ix_software_apps_winstall_detail_fingerprint", table_name="software_apps"
    )
    op.drop_index(
        "ix_software_apps_winstall_summary_fingerprint", table_name="software_apps"
    )
    op.drop_column("software_apps", "winstall_detail_fingerprint")
    op.drop_column("software_apps", "winstall_summary_fingerprint")
    op.drop_column("software_apps", "winstall_updated_at")
    op.drop_column("software_apps", "winstall_latest_version")
