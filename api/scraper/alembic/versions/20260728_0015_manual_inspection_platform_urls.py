"""Define la migración de esquema `20260728_0015_manual_inspection_platform_urls`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260728_0015"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260728_0014"
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
    """Ejecuta la operación `downgrade`.
    """
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
