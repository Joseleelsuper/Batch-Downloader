"""Define la migración de esquema `20260728_0013_website_app_discoveries`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260728_0013"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260728_0012"
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
    op.create_table(
        "website_app_discoveries",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("phase", sa.String(length=32), nullable=False),
        sa.Column("input_hash", sa.String(length=64), nullable=False),
        sa.Column("official_url_encrypted", sa.Text(), nullable=False),
        sa.Column("result_json", sa.JSON(), nullable=True),
        sa.Column("warnings_json", sa.JSON(), nullable=False),
        sa.Column("error_code", sa.String(length=120), nullable=True),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("applied_at", sa.DateTime(), nullable=True),
        sa.Column("applied_app_id", sa.BINARY(16), nullable=True),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(
            ["applied_app_id"],
            ["software_apps.id"],
            name="fk_website_app_discoveries_applied_app",
            ondelete="SET NULL",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_website_app_discoveries_hash_status",
        "website_app_discoveries",
        ["input_hash", "status", "created_at"],
    )
    op.create_index(
        "ix_website_app_discoveries_expires",
        "website_app_discoveries",
        ["expires_at"],
    )

    op.create_table(
        "website_app_discovery_installers",
        sa.Column("id", sa.BINARY(16), nullable=False),
        sa.Column("discovery_id", sa.BINARY(16), nullable=False),
        sa.Column("installer_url_encrypted", sa.Text(), nullable=False),
        sa.Column("final_domain", sa.String(length=255), nullable=True),
        sa.Column("filename", sa.String(length=255), nullable=True),
        sa.Column("extension", sa.String(length=32), nullable=True),
        sa.Column("content_type", sa.String(length=255), nullable=True),
        sa.Column("size_bytes", sa.BigInteger(), nullable=True),
        sa.Column("version", sa.String(length=100), nullable=True),
        sa.Column("operating_system", sa.String(length=32), nullable=False),
        sa.Column("architecture", sa.String(length=32), nullable=False),
        sa.Column("score", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("updated_at", sa.DateTime(), nullable=False),
        sa.ForeignKeyConstraint(
            ["discovery_id"],
            ["website_app_discoveries.id"],
            name="fk_website_app_discovery_installers_discovery",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_website_app_discovery_installers_discovery",
        "website_app_discovery_installers",
        ["discovery_id", "operating_system", "architecture"],
    )


def downgrade() -> None:
    """Ejecuta la operación `downgrade`.
    """
    op.drop_table("website_app_discovery_installers")
    op.drop_table("website_app_discoveries")
