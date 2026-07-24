from datetime import UTC, datetime, timedelta


# El almacenamiento usa UTC deliberadamente. La conversión a la zona horaria del
# usuario se realiza únicamente en los límites de presentación.
def utc_now() -> datetime:
    """Tiempo actual en UTC sin información de zona horaria.

    Returns:
        datetime: El tiempo actual en UTC sin información de zona horaria.
    """
    return datetime.now(UTC).replace(tzinfo=None)


def utc_after(**kwargs: int) -> datetime:
    """Calcula el tiempo en UTC después de un período de tiempo especificado.

    Returns:
        datetime: El tiempo en UTC después del período de tiempo especificado.
    """
    return utc_now() + timedelta(**kwargs)
