"""Comprueba los contratos que evitan filesorts y acotan caché transitoria."""
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).parents[1]
MIGRATION = ROOT / "alembic" / "versions" / "20260822_0017_runtime_memory_and_totals.py"


def test_active_run_checks_use_the_singleton_lock_without_sorting() -> None:
    """La consulta caliente no debe ordenar ni materializar el run completo."""
    worker = (ROOT / "app" / "worker.py").read_text(encoding="utf-8")
    method = worker.split("async def _paused_or_stopping", maxsplit=1)[1].split(
        "async def _scrape_run_active", maxsplit=1
    )[0]

    assert "ScrapeRun.active_lock == 1" in method
    assert "select(ScrapeRun.paused_at, ScrapeRun.stop_requested)" in method
    assert ".order_by(" not in method
    assert "select(ScrapeRun)" not in method


def test_migration_indexes_history_prunes_only_transient_data_and_exposes_totals() -> None:
    """La migración conserva tablas autoritativas y expone una vista sin recuentos."""
    migration = MIGRATION.read_text(encoding="utf-8")

    assert 'down_revision: str | None = "20260819_0016"' in migration
    assert "ix_scrape_runs_started_at" in migration
    assert "ix_scrape_runs_status_started_at" in migration
    assert "DELETE FROM scraper_worker_snapshots" in migration
    assert "DELETE FROM scraper_metric_snapshots" in migration
    assert "DELETE FROM scraper_work_items" in migration
    assert "status IN ('completed', 'discarded')" in migration
    assert "INTERVAL 30 DAY" in migration
    assert "CREATE VIEW application_totals" in migration
    assert "missing_count AS missing_installer_apps" in migration
    assert "COUNT(" not in migration
    assert "DELETE FROM scrape_runs" not in migration
    assert "DELETE FROM resolver_logs" not in migration
    assert "DELETE FROM software_apps" not in migration
