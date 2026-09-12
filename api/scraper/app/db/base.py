"""Comparte el registro declarativo y los metadatos SQLAlchemy de las tablas del scraper."""
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    """Agrupa los modelos ORM bajo los mismos metadatos para consultas y generación de
    migraciones.

    See Also:
        app.db.models: Declara tablas y relaciones sobre esta base.
        app.db.session: Abre las sesiones que consultan estos modelos.
    """
    pass
