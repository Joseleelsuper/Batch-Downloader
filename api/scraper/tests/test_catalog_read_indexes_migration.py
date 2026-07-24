from pathlib import Path

MIGRATION = (
    Path(__file__).parents[1]
    / "alembic"
    / "versions"
    / "20260716_0010_catalog_read_indexes.py"
)


def test_catalog_read_indexes_are_owned_by_alembic() -> None:
    migration = MIGRATION.read_text(encoding="utf-8")

    assert 'down_revision: str | None = "20260713_0009"' in migration
    assert "ix_software_apps_status_updated_name_id" in migration
    assert "catalog_downloadable TINYINT(1)" in migration
    assert "ix_resolved_sources_catalog_downloadable" in migration
    assert (
        "download_source_id,\n"
        "                    catalog_downloadable,\n"
        "                    checked_at"
    ) in migration
