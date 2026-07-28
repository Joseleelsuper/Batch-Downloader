"""Add optional per-platform URLs to website discoveries.

Revision ID: 20260728_0014
Revises: 20260728_0013
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260728_0014"
down_revision: str | None = "20260728_0013"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "website_app_discoveries",
        sa.Column("windows_installer_url_encrypted", sa.Text(), nullable=True),
    )
    op.add_column(
        "website_app_discoveries",
        sa.Column("macos_installer_url_encrypted", sa.Text(), nullable=True),
    )
    op.add_column(
        "website_app_discoveries",
        sa.Column("linux_installer_url_encrypted", sa.Text(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column(
        "website_app_discoveries",
        "linux_installer_url_encrypted",
    )
    op.drop_column(
        "website_app_discoveries",
        "macos_installer_url_encrypted",
    )
    op.drop_column(
        "website_app_discoveries",
        "windows_installer_url_encrypted",
    )
