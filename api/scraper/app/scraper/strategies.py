from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from typing import Protocol

from app.db.enums import ResolutionStatus
from app.scraper.candidates import InstallerCandidate
from app.scraper.winstall import WinstallApp


class ScrapeRuntime(Protocol):
    run_id: uuid.UUID


ResolverCallback = Callable[[uuid.UUID, str, WinstallApp], Awaitable[ResolutionStatus]]
ResolverPredicate = Callable[[str], bool]
CandidateResolverCallback = Callable[
    [ScrapeRuntime, WinstallApp, str], Awaitable[list[InstallerCandidate]]
]


class ResolverStrategy(Protocol):
    @property
    def name(self) -> str: ...

    def supports(self, url: str) -> bool: ...

    async def resolve(
        self,
        source_id: uuid.UUID,
        official_url: str,
        app: WinstallApp,
    ) -> ResolutionStatus: ...


@dataclass(frozen=True)
class CallbackResolverStrategy:
    """Adapter that turns existing resolver functions into registered strategies."""

    name: str
    predicate: ResolverPredicate
    callback: ResolverCallback

    def supports(self, url: str) -> bool:
        return self.predicate(url)

    async def resolve(
        self,
        source_id: uuid.UUID,
        official_url: str,
        app: WinstallApp,
    ) -> ResolutionStatus:
        return await self.callback(source_id, official_url, app)


class ResolverStrategyRegistry:
    def __init__(self, strategies: Iterable[ResolverStrategy] = ()) -> None:
        self._strategies: list[ResolverStrategy] = []
        for strategy in strategies:
            self.register(strategy)

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(strategy.name for strategy in self._strategies)

    def register(self, strategy: ResolverStrategy) -> None:
        if strategy.name in self.names:
            raise ValueError(f"resolver_strategy_already_registered:{strategy.name}")
        self._strategies.append(strategy)

    def find(self, url: str) -> ResolverStrategy | None:
        return next((strategy for strategy in self._strategies if strategy.supports(url)), None)


@dataclass(frozen=True)
class CandidateResolverStrategy:
    """A candidate-discovery strategy selected from the official source URL."""

    name: str
    predicate: ResolverPredicate
    callback: CandidateResolverCallback

    def supports(self, url: str) -> bool:
        return self.predicate(url)

    async def collect(
        self,
        runtime: ScrapeRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        return await self.callback(runtime, app, official_url)


class CandidateResolverStrategyRegistry:
    def __init__(self, strategies: Iterable[CandidateResolverStrategy] = ()) -> None:
        self._strategies: list[CandidateResolverStrategy] = []
        for strategy in strategies:
            self.register(strategy)

    @property
    def names(self) -> tuple[str, ...]:
        return tuple(strategy.name for strategy in self._strategies)

    def register(self, strategy: CandidateResolverStrategy) -> None:
        if strategy.name in self.names:
            raise ValueError(f"candidate_resolver_strategy_already_registered:{strategy.name}")
        self._strategies.append(strategy)

    def find(self, url: str) -> CandidateResolverStrategy | None:
        return next((strategy for strategy in self._strategies if strategy.supports(url)), None)
