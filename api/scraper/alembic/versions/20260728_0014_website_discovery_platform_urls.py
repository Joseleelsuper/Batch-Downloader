"""Define la migración de esquema `20260728_0014_website_discovery_platform_urls`.
"""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20260728_0014"

down_revision: str | None = "20260728_0013"

branch_labels: str | Sequence[str] | None = None

depends_on: str | Sequence[str] | None = None



def upgrade() -> None:
    """Aplica la revisión Alembic `20260728` para actualizar el esquema del Scraper de forma
    reproducible.
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
    """Revierte la revisión Alembic `20260728` en el orden inverso, conservando los
    identificadores declarados por la migración.
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
