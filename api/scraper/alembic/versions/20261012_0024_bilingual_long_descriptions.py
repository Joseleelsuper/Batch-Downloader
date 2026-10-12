"""Añade la versión inglesa de las descripciones largas del catálogo."""

from collections.abc import Sequence

import sqlalchemy as sa

from alembic import op

revision: str = "20261012_0024"
down_revision: str | None = "20260914_0021"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("software_apps", sa.Column("long_description_en", sa.Text(), nullable=True))


def downgrade() -> None:
    op.drop_column("software_apps", "long_description_en")
