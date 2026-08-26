"""Define la migración de esquema `20260709_0005_remove_domain_allowlist`.
"""

import sqlalchemy as sa

from alembic import op

revision = "20260709_0005"
"""Estado global asociado a `revision`.
"""
down_revision = "20260707_0004"
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
    op.drop_table("source_allowed_domains")


def downgrade() -> None:
    """Ejecuta la operación `downgrade`.
    """
    op.create_table(
        "source_allowed_domains",
        sa.Column("id", sa.BINARY(length=16), nullable=False),
        sa.Column("source_id", sa.BINARY(length=16), nullable=False),
        sa.Column("domain", sa.String(length=253), nullable=False),
        sa.Column("include_subdomains", sa.Boolean(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(["source_id"], ["download_sources.id"]),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("source_id", "domain", name="uq_source_allowed_domain"),
    )
