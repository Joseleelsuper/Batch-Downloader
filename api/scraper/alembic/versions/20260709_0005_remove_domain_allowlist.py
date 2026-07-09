"""remove installer domain allowlist

Revision ID: 20260709_0005
Revises: 20260707_0004
Create Date: 2026-07-09
"""

from alembic import op
import sqlalchemy as sa

revision = "20260709_0005"
down_revision = "20260707_0004"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.drop_table("source_allowed_domains")


def downgrade() -> None:
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
