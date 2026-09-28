"""Comprueba los contratos que evitan filesorts y acotan caché transitoria."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).parents[1]
MIGRATION = ROOT / "alembic" / "versions" / "20260822_0017_runtime_memory_and_totals.py"


def test_migration_prunes_only_transient_data() -> None:
    """La limpieza del historial se limita a caché temporal y trabajo terminal antiguo."""
    migration = MIGRATION.read_text(encoding="utf-8")

    assert "DELETE FROM scraper_worker_snapshots" in migration
    assert "DELETE FROM scraper_metric_snapshots" in migration
    assert "DELETE FROM scraper_work_items" in migration
    assert "status IN ('completed', 'discarded')" in migration
    assert "INTERVAL 30 DAY" in migration
    assert "DELETE FROM scrape_runs" not in migration
    assert "DELETE FROM resolver_logs" not in migration
    assert "DELETE FROM software_apps" not in migration
