"""Enforce a single active scraper coordinator.

Revision ID: 20260711_0007
Revises: 20260709_0006
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260711_0007"
down_revision: str | None = "20260709_0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("scrape_runs", sa.Column("active_lock", sa.Integer(), nullable=True))
    op.create_unique_constraint("uq_scrape_runs_active_lock", "scrape_runs", ["active_lock"])
    op.execute(
        """
        UPDATE scrape_runs
        SET active_lock = 1
        WHERE id = (
            SELECT latest.id
            FROM (
                SELECT id
                FROM scrape_runs
                WHERE status = 'running'
                ORDER BY started_at DESC
                LIMIT 1
            ) AS latest
        )
        """
    )


def downgrade() -> None:
    op.drop_constraint("uq_scrape_runs_active_lock", "scrape_runs", type_="unique")
    op.drop_column("scrape_runs", "active_lock")
