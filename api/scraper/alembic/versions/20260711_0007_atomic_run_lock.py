"""Define la migración de esquema `20260711_0007_atomic_run_lock`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260711_0007"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260709_0006"
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
    """Ejecuta la operación `downgrade`.
    """
    op.drop_constraint("uq_scrape_runs_active_lock", "scrape_runs", type_="unique")
    op.drop_column("scrape_runs", "active_lock")
