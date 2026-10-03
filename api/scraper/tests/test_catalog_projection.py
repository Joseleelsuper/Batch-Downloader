"""Comprueba el informe de consistencia y el mecanismo de reparación de proyecciones."""
from pathlib import Path

from app.repositories.catalog_projection import CatalogProjectionReport


def make_report(**overrides) -> CatalogProjectionReport:
    """Construye un informe con una proyección coherente que admite pequeñas variaciones."""
    values = {
        "source_mismatches": 0,
        "app_mismatches": 0,
        "counter_row_present": True,
        "stored_total": 7,
        "stored_available": 3,
        "stored_review": 2,
        "stored_missing": 2,
        "stored_version": 11,
        "expected_total": 7,
        "expected_available": 3,
        "expected_review": 2,
        "expected_missing": 2,
    }
    values.update(overrides)
    return CatalogProjectionReport(**values)


def test_projection_report_detects_every_kind_of_drift() -> None:
    assert make_report().consistent is True
    assert make_report(source_mismatches=1).consistent is False
    assert make_report(app_mismatches=1).consistent is False
    assert make_report(counter_row_present=False, stored_total=None).consistent is False
    assert make_report(stored_available=4).consistent is False
    assert make_report(expected_missing=3).consistent is False


def test_projection_repair_uses_transactional_singleton_lock() -> None:
    repository = (
        Path(__file__).parents[1]
        / "app"
        / "repositories"
        / "catalog_projection.py"
    ).read_text(encoding="utf-8")

    assert "INSERT IGNORE INTO catalog_counters" in repository
    assert "SELECT id FROM catalog_counters WHERE id = 1 FOR UPDATE" in repository
    assert "GET_LOCK" not in repository
    assert "RELEASE_LOCK" not in repository
