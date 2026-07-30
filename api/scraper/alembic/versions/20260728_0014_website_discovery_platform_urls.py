"""Define la migración de esquema `20260728_0014_website_discovery_platform_urls`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260728_0014"
"""Estado global asociado a `revision`.
"""
down_revision: str | None = "20260728_0013"
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
    """Ejecuta la operación `downgrade`.
    """
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
