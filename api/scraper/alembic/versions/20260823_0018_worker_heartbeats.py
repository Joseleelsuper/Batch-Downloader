"""Añade una señal persistente y acotada para el scheduler del scraper."""

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import mysql

from alembic import op

revision: str = "20260823_0018"
down_revision: str | None = "20260822_0017"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Crea una fila por rol con último éxito/error y contador de fallos."""
    op.create_table(
        "scraper_worker_heartbeats",
        sa.Column("role", sa.String(length=64), primary_key=True),
        sa.Column("instance_id", sa.BINARY(length=16), nullable=False),
        sa.Column(
            "started_at",
            mysql.DATETIME(fsp=6),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP(6)"),
        ),
        sa.Column(
            "heartbeat_at",
            mysql.DATETIME(fsp=6),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP(6)"),
        ),
        sa.Column("last_success_at", mysql.DATETIME(fsp=6)),
        sa.Column("last_error_at", mysql.DATETIME(fsp=6)),
        sa.Column("last_error_code", sa.String(length=128)),
        sa.Column(
            "consecutive_failures",
            sa.Integer(),
            nullable=False,
            server_default="0",
        ),
        sa.CheckConstraint(
            "consecutive_failures >= 0",
            name="ck_scraper_worker_heartbeat_failures_nonnegative",
        ),
    )
    op.create_index(
        "ix_scraper_worker_heartbeats_heartbeat",
        "scraper_worker_heartbeats",
        ["heartbeat_at"],
    )


def downgrade() -> None:
    """Retira la señal; no modifica ejecuciones ni colas."""
    op.drop_index(
        "ix_scraper_worker_heartbeats_heartbeat",
        table_name="scraper_worker_heartbeats",
    )
    op.drop_table("scraper_worker_heartbeats")
