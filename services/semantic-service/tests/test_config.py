"""Verifica los presupuestos de PostgreSQL y la ventana fuera de punta."""

from datetime import datetime
from zoneinfo import ZoneInfo

from app.config import Settings


def test_role_specific_pool_limits() -> None:
    """Cada proceso utiliza exclusivamente su presupuesto declarado."""
    assert Settings(database_role="api").database_pool_limits == (1, 3)
    assert Settings(database_role="indexer").database_pool_limits == (0, 1)
    assert Settings(database_role="model_worker").database_pool_limits == (0, 1)


def test_background_work_only_starts_inside_configured_window() -> None:
    """La ventana 01:00-07:00 se evalúa en la zona horaria configurada."""
    settings = Settings(
        background_timezone="Europe/Madrid",
        background_start_hour=1,
        background_end_hour=7,
    )
    zone = ZoneInfo("Europe/Madrid")

    assert settings.background_window_open(datetime(2026, 8, 5, 3, tzinfo=zone))
    assert not settings.background_window_open(
        datetime(2026, 8, 5, 12, tzinfo=zone)
    )
