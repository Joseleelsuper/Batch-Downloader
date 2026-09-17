"""Verifica límites de conexiones por rol y la ventana horaria del trabajo de fondo."""

from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

from app.config import Settings


def test_role_specific_pool_limits() -> None:
    """API e indexador conservan sus presupuestos de conexiones."""
    assert Settings(database_role="api").database_pool_limits == (1, 3)
    assert Settings(database_role="indexer").database_pool_limits == (0, 1)


def test_model_slot_defaults_to_current_folder() -> None:
    settings = Settings()
    assert settings.model_dir == "/models/current"
    assert Path(settings.model_manifest_path).name == "batch-model.json"


def test_background_work_only_starts_inside_configured_window() -> None:
    """La ventana madrileña de una a siete permite las tres de la mañana y rechaza el mediodía."""
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
