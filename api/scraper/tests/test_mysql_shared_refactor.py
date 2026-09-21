"""Contratos estáticos de la refactorización incremental del MySQL compartido."""
from pathlib import Path

from app.db.models import ResolvedSource, ScraperCommand, ScrapeRun, SoftwareApp

ROOT = Path(__file__).parents[1]
VERSIONS = ROOT / "alembic" / "versions"


def test_expand_migration_guards_history_and_adds_target_indexes() -> None:
    migration = (VERSIONS / "20260914_0021_mysql_shared_expand.py").read_text(
        encoding="utf-8"
    )

    assert 'down_revision: str | None = "20260907_0020"' in migration
    assert "0021_aborted_duplicate_scrape_run_request_id" in migration
    assert "0021_aborted_orphan_scrape_run_request" in migration
    assert "0021_aborted_legacy_run_link_mismatch" in migration
    assert "0021_aborted_null_artifact_fingerprint" in migration
    assert "_backfill_missing_artifact_fingerprints" in migration
    assert "uq_scrape_runs_request_id" in migration
    assert "fk_scrape_runs_request_command" in migration
    assert "uq_resolved_sources_source_fingerprint" in migration
    assert "ix_software_apps_os_refresh" in migration
    assert "ALTER INDEX" in migration


def test_contract_migration_removes_only_compatibility_objects() -> None:
    migration = (VERSIONS / "20260914_0022_mysql_shared_contract.py").read_text(
        encoding="utf-8"
    )

    assert 'down_revision: str | None = "20260914_0021"' in migration
    assert '"run_id"' in migration
    assert "application_totals" in migration
    assert "ix_resolved_sources_status" in migration
    assert "ix_scrape_runs_status" in migration
    assert "ix_software_app_tags_app" in migration
    assert "ix_software_apps_app_status" in migration
    assert "op.execute(sa.text(\"OPTIMIZE TABLE" not in migration
    assert migration.index("_assert_evaluation_indexes_are_invisible(bind)") < migration.index(
        'if "run_id" in _column_names'
    )


def test_flyway_projection_removal_aborts_when_a_legacy_table_is_used() -> None:
    migration = (
        ROOT.parents[1]
        / "services"
        / "core-api"
        / "src"
        / "main"
        / "resources"
        / "db"
        / "migration"
        / "V17__remove_catalog_projection_tables.sql"
    ).read_text(encoding="utf-8")

    assert "DROP PROCEDURE IF EXISTS" in migration
    assert "SIGNAL SQLSTATE '45000'" in migration
    assert migration.index("catalog_source_projections") < migration.index(
        "catalog_app_projections"
    )


def test_companion_entrypoints_hold_contractive_migrations_behind_a_gate() -> None:
    entrypoint = (ROOT / "scripts" / "docker-entrypoint.sh").read_text(encoding="utf-8")
    assert 'SCRAPER_ALEMBIC_TARGET:-20260914_0021' in entrypoint
    properties = (
        ROOT.parents[1]
        / "services"
        / "core-api"
        / "src"
        / "main"
        / "resources"
        / "application.properties"
    ).read_text(encoding="utf-8")
    assert "spring.flyway.target=${CORE_API_FLYWAY_TARGET:16.2}" in properties


def test_models_publish_new_relationship_and_access_paths() -> None:
    assert ScrapeRun.request_id.unique is True
    assert any(
        index.name == "ix_software_apps_os_refresh"
        for index in SoftwareApp.__table__.indexes
    )
    assert any(
        constraint.name == "uq_resolved_sources_source_fingerprint"
        for constraint in ResolvedSource.__table__.constraints
    )
    assert ResolvedSource.__table__.c.artifact_fingerprint.nullable is False
    assert "run_id" not in ScrapeRun.__table__.columns
    assert "run_id" not in ScraperCommand.__table__.columns
