"""Implementa las responsabilidades del módulo `text`.
"""
import re
import unicodedata


def normalize_text(value: str | None) -> str:
    """Normaliza la operación `text`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    if not value:
        return ""
    text = unicodedata.normalize("NFKD", value)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.lower()
    return re.sub(r"\s+", " ", text).strip()


def slugify(value: str) -> str:
    """Ejecuta la operación `slugify`.

    Args:
        value (str): Valor que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    normalized = normalize_text(value)
    normalized = re.sub(r"[^a-z0-9]+", "-", normalized).strip("-")
    return normalized or "app"
