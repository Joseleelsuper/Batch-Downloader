"""Define la migración de esquema `20260713_0009_so_filter_pipeline`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260713_0009"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260713_0008"
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
        "software_apps",
        sa.Column("operating_systems_json", sa.JSON(), nullable=True),
    )
    op.add_column(
        "software_apps",
        sa.Column("operating_systems_updated_at", sa.DateTime(), nullable=True),
    )
    empty_json = "JSON_ARRAY()" if op.get_bind().dialect.name == "mysql" else "'[]'"
    op.execute(
        sa.text(
            f"UPDATE software_apps SET operating_systems_json = {empty_json} "
            "WHERE operating_systems_json IS NULL"
        )
    )
    op.alter_column(
        "software_apps",
        "operating_systems_json",
        existing_type=sa.JSON(),
        nullable=False,
    )

    op.execute(
        sa.text(
            "UPDATE scraper_work_items SET queue = 'so_filter_descriptor' "
            "WHERE queue = 'scraper_descriptor'"
        )
    )
    op.execute(sa.text("DELETE FROM scraper_work_items WHERE queue = 'icon_enrichment'"))
    op.execute(sa.text("DELETE FROM scraper_worker_snapshots WHERE stage = 'icon'"))

    op.alter_column(
        "scraper_metric_snapshots",
        "queued_scraper_descriptor",
        new_column_name="queued_so_filter_descriptor",
        existing_type=sa.Integer(),
        existing_nullable=False,
    )
    op.alter_column(
        "scraper_metric_snapshots",
        "queued_icon_enrichment",
        new_column_name="queued_scraper_so_filter",
        existing_type=sa.Integer(),
        existing_nullable=False,
    )
    op.execute(sa.text("UPDATE scraper_metric_snapshots SET queued_scraper_so_filter = 0"))


def downgrade() -> None:
    """Ejecuta la operación `downgrade`.
    """
    op.execute(sa.text("DELETE FROM scraper_work_items WHERE queue = 'scraper_so_filter'"))
    op.execute(
        sa.text(
            "UPDATE scraper_work_items SET queue = 'scraper_descriptor' "
            "WHERE queue = 'so_filter_descriptor'"
        )
    )
    op.alter_column(
        "scraper_metric_snapshots",
        "queued_so_filter_descriptor",
        new_column_name="queued_scraper_descriptor",
        existing_type=sa.Integer(),
        existing_nullable=False,
    )
    op.alter_column(
        "scraper_metric_snapshots",
        "queued_scraper_so_filter",
        new_column_name="queued_icon_enrichment",
        existing_type=sa.Integer(),
        existing_nullable=False,
    )
    op.drop_column("software_apps", "operating_systems_updated_at")
    op.drop_column("software_apps", "operating_systems_json")
