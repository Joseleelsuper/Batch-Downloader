"""Configuración y metadatos del contrato MySQL compartido."""
from pathlib import Path

from app.db.models import ResolvedSource, ScraperCommand, ScrapeRun, SoftwareApp

ROOT = Path(__file__).parents[1]


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
    assert "spring.flyway.target=${CORE_API_FLYWAY_TARGET:20}" in properties


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
