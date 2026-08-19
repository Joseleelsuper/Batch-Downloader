"""Pruebas del extractor dinámico de candidatos."""

import pytest

from app.scraper.playwright_fallback import collect_page_candidates


class FakeLocator:
    """Simula la consulta de atributos dinámicos de Playwright."""

    async def evaluate_all(self, _expression: str) -> list[str]:
        return [
            "https://downloads.sourceforge.net/project/example/AppSetup.exe"
            "?ts=signed&use_mirror=active"
        ]


class FakePage:
    """Página mínima para probar la extracción sin lanzar Chromium."""

    def locator(self, selector: str) -> FakeLocator:
        assert selector == "[data-release-url]"
        return FakeLocator()

    async def content(self) -> str:
        return (
            "<html><body><a data-release-url='ignored'>"
            "Problems Downloading?</a></body></html>"
        )


@pytest.mark.asyncio
async def test_sourceforge_signed_release_url_keeps_stable_referer() -> None:
    """La firma temporal se valida, pero la URL estable queda como canónica."""
    collected = {}
    stable_url = (
        "https://sourceforge.net/projects/example/files/1.0/AppSetup.exe/download"
    )

    await collect_page_candidates(collected, FakePage(), stable_url)

    candidate = next(iter(collected.values()))
    assert candidate.source == "playwright_data_release_url"
    assert candidate.referer == stable_url
    assert candidate.url.startswith(
        "https://downloads.sourceforge.net/project/example/"
    )
