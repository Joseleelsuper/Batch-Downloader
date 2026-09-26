"""Pruebas del contrato de estadísticas públicas del catálogo."""
from datetime import datetime

from app.schemas.apps import CatalogFilterStats, CatalogStatsResponse


def test_catalog_stats_response_exposes_counts_and_generated_at_only() -> None:
    """Las estadísticas mantienen sus contadores y fecha sin incluir el estado del scraper."""
    response = CatalogStatsResponse(
        total=3,
        filters=CatalogFilterStats(all=3, available=2, review=1, missing=0),
        generated_at=datetime(2026, 9, 26, 3, 0),
    )

    payload = response.model_dump(by_alias=True)

    assert payload["total"] == 3
    assert payload["generatedAt"] == datetime(2026, 9, 26, 3, 0)
    assert "lastScrape" not in payload
