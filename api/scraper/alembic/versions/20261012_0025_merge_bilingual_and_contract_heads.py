"""Une la rama bilingüe con la rama contractiva que sigue detrás de su puerta."""

from collections.abc import Sequence

revision: str = "20261012_0025"
down_revision: tuple[str, str] = ("20260920_0023", "20261012_0024")
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Registra ambas ramas cuando se autorice superar la puerta contractiva."""


def downgrade() -> None:
    """La revisión de combinación no altera el esquema."""
