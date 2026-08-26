"""Implementa las responsabilidades del módulo `strategies`.
"""
from __future__ import annotations

import uuid
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from typing import Protocol

from app.db.enums import ResolutionStatus
from app.scraper.candidates import InstallerCandidate
from app.scraper.winstall import WinstallApp


class ScrapeRuntime(Protocol):
    """Mantiene el estado de ejecución de `Scrape`.
    """
    run_id: uuid.UUID
    """Atributo de clase `run_id` de `ScrapeRuntime`.
    """


ResolverCallback = Callable[[uuid.UUID, str, WinstallApp], Awaitable[ResolutionStatus]]
"""Estado global asociado a `ResolverCallback`.
"""
ResolverPredicate = Callable[[str], bool]
"""Estado global asociado a `ResolverPredicate`.
"""
CandidateResolverCallback = Callable[
    [ScrapeRuntime, WinstallApp, str], Awaitable[list[InstallerCandidate]]
]
"""Estado global asociado a `CandidateResolverCallback`.
"""


class ResolverStrategy(Protocol):
    """Representa el componente `ResolverStrategy`.
    """
    @property
    def name(self) -> str:
        """Ejecuta `name` dentro de `ResolverStrategy`.

        Returns:
            str: Resultado producido por la operación.
        """
        ...

    def supports(self, url: str) -> bool:
        """Ejecuta `supports` dentro de `ResolverStrategy`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        ...

    async def resolve(
        self,
        source_id: uuid.UUID,
        official_url: str,
        app: WinstallApp,
    ) -> ResolutionStatus:
        """Ejecuta `resolve` dentro de `ResolverStrategy`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            official_url (str): Dirección de `official` que debe procesarse.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            ResolutionStatus: Resultado producido por la operación.
        """
        ...


@dataclass(frozen=True)
class CallbackResolverStrategy:
    """Representa el componente `CallbackResolverStrategy`.
    """

    name: str
    """Atributo de clase `name` de `CallbackResolverStrategy`.
    """
    predicate: ResolverPredicate
    """Atributo de clase `predicate` de `CallbackResolverStrategy`.
    """
    callback: ResolverCallback
    """Atributo de clase `callback` de `CallbackResolverStrategy`.
    """

    def supports(self, url: str) -> bool:
        """Ejecuta `supports` dentro de `CallbackResolverStrategy`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return self.predicate(url)

    async def resolve(
        self,
        source_id: uuid.UUID,
        official_url: str,
        app: WinstallApp,
    ) -> ResolutionStatus:
        """Ejecuta `resolve` dentro de `CallbackResolverStrategy`.

        Args:
            source_id (uuid.UUID): Identificador de `source` utilizado por la operación.
            official_url (str): Dirección de `official` que debe procesarse.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            ResolutionStatus: Resultado producido por la operación.
        """
        return await self.callback(source_id, official_url, app)


class ResolverStrategyRegistry:
    """Representa el componente `ResolverStrategyRegistry`.
    """
    def __init__(self, strategies: Iterable[ResolverStrategy] = ()) -> None:
        """Inicializa una instancia de `ResolverStrategyRegistry`.

        Args:
            strategies (Iterable[ResolverStrategy]): Valor de `strategies` utilizado por la
                operación.
        """
        self._strategies: list[ResolverStrategy] = []
        """Estado de instancia asociado a `_strategies`.
        """
        for strategy in strategies:
            self.register(strategy)

    @property
    def names(self) -> tuple[str, ...]:
        """Ejecuta `names` dentro de `ResolverStrategyRegistry`.

        Returns:
            tuple[str, ...]: Resultado producido por la operación.
        """
        return tuple(strategy.name for strategy in self._strategies)

    def register(self, strategy: ResolverStrategy) -> None:
        """Ejecuta `register` dentro de `ResolverStrategyRegistry`.

        Args:
            strategy (ResolverStrategy): Valor de `strategy` utilizado por la operación.

        Throws:
            ValueError: Si los datos recibidos no cumplen las restricciones requeridas.
        """
        if strategy.name in self.names:
            raise ValueError(f"resolver_strategy_already_registered:{strategy.name}")
        self._strategies.append(strategy)

    def find(self, url: str) -> ResolverStrategy | None:
        """Ejecuta `find` dentro de `ResolverStrategyRegistry`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            ResolverStrategy | None: Resultado producido por la operación.
        """
        return next((strategy for strategy in self._strategies if strategy.supports(url)), None)


@dataclass(frozen=True)
class CandidateResolverStrategy:
    """Representa el componente `CandidateResolverStrategy`.
    """

    name: str
    """Atributo de clase `name` de `CandidateResolverStrategy`.
    """
    predicate: ResolverPredicate
    """Atributo de clase `predicate` de `CandidateResolverStrategy`.
    """
    callback: CandidateResolverCallback
    """Atributo de clase `callback` de `CandidateResolverStrategy`.
    """

    def supports(self, url: str) -> bool:
        """Ejecuta `supports` dentro de `CandidateResolverStrategy`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        return self.predicate(url)

    async def collect(
        self,
        runtime: ScrapeRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        """Ejecuta `collect` dentro de `CandidateResolverStrategy`.

        Args:
            runtime (ScrapeRuntime): Valor de `runtime` utilizado por la operación.
            app (WinstallApp): Aplicación sobre la que se realiza la operación.
            official_url (str): Dirección de `official` que debe procesarse.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        return await self.callback(runtime, app, official_url)


class CandidateResolverStrategyRegistry:
    """Representa el componente `CandidateResolverStrategyRegistry`.
    """
    def __init__(self, strategies: Iterable[CandidateResolverStrategy] = ()) -> None:
        """Inicializa una instancia de `CandidateResolverStrategyRegistry`.

        Args:
            strategies (Iterable[CandidateResolverStrategy]): Valor de `strategies` utilizado por la
                operación.
        """
        self._strategies: list[CandidateResolverStrategy] = []
        """Estado de instancia asociado a `_strategies`.
        """
        for strategy in strategies:
            self.register(strategy)

    @property
    def names(self) -> tuple[str, ...]:
        """Ejecuta `names` dentro de `CandidateResolverStrategyRegistry`.

        Returns:
            tuple[str, ...]: Resultado producido por la operación.
        """
        return tuple(strategy.name for strategy in self._strategies)

    def register(self, strategy: CandidateResolverStrategy) -> None:
        """Ejecuta `register` dentro de `CandidateResolverStrategyRegistry`.

        Args:
            strategy (CandidateResolverStrategy): Valor de `strategy` utilizado por la operación.

        Throws:
            ValueError: Si los datos recibidos no cumplen las restricciones requeridas.
        """
        if strategy.name in self.names:
            raise ValueError(f"candidate_resolver_strategy_already_registered:{strategy.name}")
        self._strategies.append(strategy)

    def find(self, url: str) -> CandidateResolverStrategy | None:
        """Ejecuta `find` dentro de `CandidateResolverStrategyRegistry`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            CandidateResolverStrategy | None: Resultado producido por la operación.
        """
        return next((strategy for strategy in self._strategies if strategy.supports(url)), None)
