"""Punto de entrada exclusivo para las migraciones de PostgreSQL semántico."""
from __future__ import annotations

from app.config import get_settings
from app.database import Database


def main() -> None:
    """Aplica todas las migraciones con advisory lock y cierra la conexión."""
    database = Database(get_settings())
    database.open()
    try:
        database.migrate()
    finally:
        database.close()


if __name__ == "__main__":
    main()
