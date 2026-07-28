"""Add optional per-platform URLs to manual installer inspections.

Revision ID: 20260728_0015
Revises: 20260728_0014
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260728_0015"
down_revision: str | None = "20260728_0014"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.alter_column(
        "manual_installer_inspections",
        "installer_url_encrypted",
        existing_type=sa.Text(),
        nullable=True,
    )
    op.add_column(
        "manual_installer_inspections",
        sa.Column("windows_installer_url_encrypted", sa.Text(), nullable=True),
    )
    op.add_column(
        "manual_installer_inspections",
        sa.Column("macos_installer_url_encrypted", sa.Text(), nullable=True),
    )
    op.add_column(
        "manual_installer_inspections",
        sa.Column("linux_installer_url_encrypted", sa.Text(), nullable=True),
    )


def downgrade() -> None:
    op.execute(
        """
        UPDATE manual_installer_inspections
        SET installer_url_encrypted = COALESCE(
            installer_url_encrypted,
            windows_installer_url_encrypted,
            macos_installer_url_encrypted,
            linux_installer_url_encrypted
        )
        WHERE installer_url_encrypted IS NULL
        """
    )
    op.drop_column(
        "manual_installer_inspections",
        "linux_installer_url_encrypted",
    )
    op.drop_column(
        "manual_installer_inspections",
        "macos_installer_url_encrypted",
    )
    op.drop_column(
        "manual_installer_inspections",
        "windows_installer_url_encrypted",
    )
    op.alter_column(
        "manual_installer_inspections",
        "installer_url_encrypted",
        existing_type=sa.Text(),
        nullable=False,
    )
