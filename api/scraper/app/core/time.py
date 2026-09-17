"""Proporciona instantes UTC sin tzinfo compatibles con las columnas temporales del scraper."""
from datetime import UTC, datetime, timedelta


# El almacenamiento usa UTC deliberadamente. La conversión a la zona horaria del
# usuario se realiza únicamente en los límites de presentación.
def utc_now() -> datetime:
    """Lee el reloj UTC y retira tzinfo para mantener la convención temporal de persistencia.

    Returns:
        instante UTC ingenuo, que no representa la hora local del equipo.
    """
    return datetime.now(UTC).replace(tzinfo=None)


def utc_after(**kwargs: int) -> datetime:
    """Suma al instante UTC actual un desplazamiento expresado con los argumentos de timedelta.

    Args:
        kwargs: Unidades de timedelta, como seconds, minutes u hours; se admiten valores
            negativos.

    Returns:
        instante desplazado en UTC sin tzinfo.
    """
    return utc_now() + timedelta(**kwargs)
