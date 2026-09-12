"""Define la migración de esquema `20260711_0007_atomic_run_lock`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260711_0007"

down_revision: str | None = "20260709_0006"

branch_labels: str | Sequence[str] | None = None

depends_on: str | Sequence[str] | None = None



def upgrade() -> None:
    """Aplica la revisión Alembic `20260711` para actualizar el esquema del Scraper de forma
    reproducible.
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
    """Revierte la revisión Alembic `20260711` en el orden inverso, conservando los
    identificadores declarados por la migración.
    """
    op.drop_constraint("uq_scrape_runs_active_lock", "scrape_runs", type_="unique")
    op.drop_column("scrape_runs", "active_lock")
