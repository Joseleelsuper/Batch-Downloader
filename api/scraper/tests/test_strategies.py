"""Contiene las pruebas de `test_strategies`.
"""
import uuid

import pytest

from app.core.config import Settings
from app.db.enums import ResolutionStatus
from app.scraper.candidates import InstallerCandidate
from app.scraper.catalog_fetcher import PlatformScraperWorker
from app.scraper.ports import CatalogProvider
from app.scraper.strategies import (
    CallbackResolverStrategy,
    CandidateResolverStrategy,
    CandidateResolverStrategyRegistry,
    ResolverStrategyRegistry,
)
from app.scraper.winstall import WinstallApp, WinstallClient


def make_app() -> WinstallApp:
    """Construye la operación `app`.

    Returns:
        WinstallApp: Resultado producido por la operación.
    """
    return WinstallApp(
        package_id="Vendor.App",
        name="Vendor App",
        description=None,
        publisher="Vendor",
        homepage="https://example.test",
        icon=None,
        icon_url=None,
        latest_version="1.0",
        tags=[],
        versions=[],
        raw={},
    )


@pytest.mark.asyncio
async def test_resolver_registry_selects_first_supporting_strategy() -> None:
    """Comprueba el escenario `resolver_registry_selects_first_supporting_strategy`.
    """
    calls: list[str] = []

    async def github_callback(_source_id, _url, _app):
        """Ejecuta la operación `github_callback`.

        Args:
            _source_id (Any): Identificador de `_source` utilizado por la operación.
            _url (Any): Dirección de `` que debe procesarse.
            _app (Any): Valor de `_app` utilizado por la operación.
        """
        calls.append("github")
        return ResolutionStatus.DIRECT

    async def generic_callback(_source_id, _url, _app):
        """Ejecuta la operación `generic_callback`.

        Args:
            _source_id (Any): Identificador de `_source` utilizado por la operación.
            _url (Any): Dirección de `` que debe procesarse.
            _app (Any): Valor de `_app` utilizado por la operación.
        """
        calls.append("generic")
        return ResolutionStatus.REQUIRES_MANUAL_REVIEW

    registry = ResolverStrategyRegistry(
        (
            CallbackResolverStrategy(
                "github",
                lambda url: "github.com" in url,
                github_callback,
            ),
            CallbackResolverStrategy("generic", lambda _url: True, generic_callback),
        )
    )

    strategy = registry.find("https://github.com/vendor/app")

    assert strategy is not None
    assert await strategy.resolve(uuid.uuid4(), "https://github.com/vendor/app", make_app()) == (
        ResolutionStatus.DIRECT
    )
    assert calls == ["github"]


def test_resolver_registry_rejects_duplicate_names() -> None:
    """Comprueba el escenario `resolver_registry_rejects_duplicate_names`.
    """
    async def callback(_source_id, _url, _app):
        """Procesa la devolución de llamada asociada a la operación.

        Args:
            _source_id (Any): Identificador de `_source` utilizado por la operación.
            _url (Any): Dirección de `` que debe procesarse.
            _app (Any): Valor de `_app` utilizado por la operación.
        """
        return ResolutionStatus.DIRECT

    registry = ResolverStrategyRegistry(
        (CallbackResolverStrategy("known_endpoint", lambda _url: True, callback),)
    )

    with pytest.raises(ValueError, match="resolver_strategy_already_registered"):
        registry.register(CallbackResolverStrategy("known_endpoint", lambda _url: True, callback))


def test_winstall_client_implements_catalog_provider_port() -> None:
    """Comprueba el escenario `winstall_client_implements_catalog_provider_port`.
    """
    assert isinstance(WinstallClient(Settings()), CatalogProvider)


@pytest.mark.asyncio
async def test_platform_worker_uses_injected_candidate_resolver_strategy() -> None:
    """Comprueba el escenario `platform_worker_uses_injected_candidate_resolver_strategy`.
    """
    collected = InstallerCandidate(
        url="https://downloads.example.test/AppSetup.exe",
        source="custom",
    )

    async def collect(_runtime, _app, _url):
        """Ejecuta la operación `collect`.

        Args:
            _runtime (Any): Valor de `_runtime` utilizado por la operación.
            _app (Any): Valor de `_app` utilizado por la operación.
            _url (Any): Dirección de `` que debe procesarse.
        """
        return [collected]

    registry = CandidateResolverStrategyRegistry(
        (CandidateResolverStrategy("custom", lambda _url: True, collect),)
    )
    worker = PlatformScraperWorker(Settings(), candidate_resolvers=registry)
    runtime = type("Runtime", (), {"run_id": uuid.uuid4()})()

    candidates = await worker._collect_official_candidates(
        runtime,
        make_app(),
        "https://example.test/downloads",
    )

    assert candidates == [collected]
