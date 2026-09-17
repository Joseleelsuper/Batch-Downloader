"""Normaliza texto de catálogo para búsquedas, coincidencias y slugs reproducibles."""
import re
import unicodedata


def normalize_text(value: str | None) -> str:
    """Descompone Unicode con NFKD, elimina marcas combinantes, convierte a minúsculas y colapsa
    espacios.

    Args:
        value: Texto, URL o atributo que se normaliza o analiza.

    Returns:
        texto normalizado o cadena vacía si falta el valor.
    """
    if not value:
        return ""
    text = unicodedata.normalize("NFKD", value)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.lower()
    return re.sub(r"\s+", " ", text).strip()


def slugify(value: str) -> str:
    """Convierte el texto normalizado a segmentos ASCII alfanuméricos separados por guiones.

    Args:
        value: Texto, URL o atributo que se normaliza o analiza.

    Returns:
        slug sin guiones exteriores, o app si no queda contenido.
    """
    normalized = normalize_text(value)
    normalized = re.sub(r"[^a-z0-9]+", "-", normalized).strip("-")
    return normalized or "app"
