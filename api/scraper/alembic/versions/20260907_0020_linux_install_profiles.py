"""Perfiles Linux y dependencias entre aplicaciones."""

import sqlalchemy as sa

from alembic import op

revision = "20260907_0020"
down_revision = "20260901_0019"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "linux_install_profiles",
        sa.Column(
            "source_ref",
            sa.BINARY(16),
            sa.ForeignKey("resolved_sources.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("version", sa.BigInteger(), nullable=False, server_default="1"),
        sa.Column("status", sa.String(16), nullable=False),
        sa.Column("profile_json", sa.JSON(), nullable=False),
    )
    op.create_table(
        "software_app_dependency_versions",
        sa.Column(
            "app_id",
            sa.BINARY(16),
            sa.ForeignKey("software_apps.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("version", sa.BigInteger(), nullable=False, server_default="0"),
    )
    op.create_table(
        "software_app_dependencies",
        sa.Column(
            "app_id",
            sa.BINARY(16),
            sa.ForeignKey("software_apps.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column(
            "dependency_app_id",
            sa.BINARY(16),
            sa.ForeignKey("software_apps.id", ondelete="CASCADE"),
            primary_key=True,
        ),
    )


def downgrade():
    op.drop_table("software_app_dependencies")
    op.drop_table("software_app_dependency_versions")
    op.drop_table("linux_install_profiles")
