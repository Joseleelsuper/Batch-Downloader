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
    calls: list[str] = []

    async def github_callback(_source_id, _url, _app):
        calls.append("github")
        return ResolutionStatus.DIRECT

    async def generic_callback(_source_id, _url, _app):
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
    async def callback(_source_id, _url, _app):
        return ResolutionStatus.DIRECT

    registry = ResolverStrategyRegistry(
        (CallbackResolverStrategy("known_endpoint", lambda _url: True, callback),)
    )

    with pytest.raises(ValueError, match="resolver_strategy_already_registered"):
        registry.register(CallbackResolverStrategy("known_endpoint", lambda _url: True, callback))


def test_winstall_client_implements_catalog_provider_port() -> None:
    assert isinstance(WinstallClient(Settings()), CatalogProvider)


@pytest.mark.asyncio
async def test_platform_worker_uses_injected_candidate_resolver_strategy() -> None:
    collected = InstallerCandidate(
        url="https://downloads.example.test/AppSetup.exe",
        source="custom",
    )

    async def collect(_runtime, _app, _url):
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
