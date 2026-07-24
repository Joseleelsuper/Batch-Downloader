from pathlib import Path

from app.repositories.catalog_projection import CatalogProjectionReport

MIGRATION = (
    Path(__file__).parents[1]
    / "alembic"
    / "versions"
    / "20260718_0011_catalog_projection.py"
)


def make_report(**overrides) -> CatalogProjectionReport:
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


def test_projection_migration_owns_backfill_and_incremental_triggers() -> None:
    migration = MIGRATION.read_text(encoding="utf-8")

    assert 'down_revision: str | None = "20260716_0010"' in migration
    assert "CREATE TABLE catalog_counters" in migration
    assert "total_count = available_count + review_count + missing_count" in migration
    assert "catalog_downloadable_count INT UNSIGNED" in migration
    assert "catalog_available TINYINT(1)" in migration
    assert "catalog_available_source_count INT UNSIGNED" in migration
    assert "catalog_review_source_count INT UNSIGNED" in migration
    assert "catalog_status VARCHAR(16)" in migration
    assert "WHEN catalog_available_source_count > 0 THEN 'available'" in migration
    assert "WHEN catalog_review_source_count > 0 THEN 'review'" in migration
    assert "ELSE 'missing'" in migration

    for table in ("resolved_sources", "download_sources", "software_apps"):
        for event in ("ai", "au", "ad"):
            name = f"trg_{table}_catalog_{event}"
            assert f"CREATE TRIGGER {name}" in migration
            assert f'"{name}"' in migration
    assert 'f"DROP TRIGGER IF EXISTS {trigger}"' in migration
    assert "for trigger in reversed(TRIGGERS)" in migration
    assert migration.index('"trg_resolved_sources_catalog_ai"') < migration.index(
        '"trg_download_sources_catalog_ai"'
    ) < migration.index('"trg_software_apps_catalog_ai"')
    assert "IF NOT (OLD.catalog_status <=> NEW.catalog_status) THEN" in migration


def test_projection_does_not_use_freshness_as_catalog_classification() -> None:
    migration = MIGRATION.read_text(encoding="utf-8")
    backfill = migration.split("# Backfill once", maxsplit=1)[1].split(
        "_create_software_app_triggers()",
        maxsplit=1,
    )[0]

    assert "catalog_downloadable = 1" in backfill
    assert "checked_at" not in backfill
    assert "expires_at" not in backfill


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
