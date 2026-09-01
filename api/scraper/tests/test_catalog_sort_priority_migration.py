"""Valida la migración que elimina el filesort global del catálogo."""

from pathlib import Path

MIGRATION = (
    Path(__file__).parents[1]
    / "alembic"
    / "versions"
    / "20260901_0019_catalog_sort_priority.py"
)


def test_catalog_sort_priority_is_materialized_and_indexed() -> None:
    """Conserva review al final con índices para los dos órdenes no alfabéticos."""
    migration = MIGRATION.read_text(encoding="utf-8")

    assert 'down_revision: str | None = "20260823_0018"' in migration
    assert "catalog_review_priority TINYINT(1)" in migration
    assert "CASE WHEN catalog_status = 'review' THEN 1 ELSE 0 END" in migration
    assert "ix_software_apps_catalog_review_updated" in migration
    assert (
        "app_status,\n"
        "                    catalog_review_priority,\n"
        "                    updated_at DESC,\n"
        "                    normalized_name,\n"
        "                    id"
    ) in migration
