"""Implementa las responsabilidades del módulo `time`.
"""
from datetime import UTC, datetime, timedelta


# El almacenamiento usa UTC deliberadamente. La conversión a la zona horaria del
# usuario se realiza únicamente en los límites de presentación.
def utc_now() -> datetime:
    """Ejecuta la operación `utc_now`.

    Returns:
        datetime: Resultado producido por la operación.
    """
    return datetime.now(UTC).replace(tzinfo=None)


def utc_after(**kwargs: int) -> datetime:
    """Ejecuta la operación `utc_after`.

    Args:
        **kwargs (int): Valor de `kwargs` utilizado por la operación.

    Returns:
        datetime: Resultado producido por la operación.
    """
    return utc_now() + timedelta(**kwargs)
