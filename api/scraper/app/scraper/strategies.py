"""Selecciona políticas por proveedor mediante registros ordenados y callbacks que separan
recopilación de candidatos y resolución persistida.
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
    """Expone la identidad mínima de ejecución necesaria por una estrategia de recopilación.

    Attributes:
        run_id: UUID de la ejecución a la que se atribuyen progreso y evidencia.
    """
    run_id: uuid.UUID



ResolverCallback = Callable[[uuid.UUID, str, WinstallApp], Awaitable[ResolutionStatus]]

ResolverPredicate = Callable[[str], bool]

CandidateResolverCallback = Callable[
    [ScrapeRuntime, WinstallApp, str], Awaitable[list[InstallerCandidate]]
]



class ResolverStrategy(Protocol):
    """Define selección por URL y ejecución de un resolutor que actualiza una fuente existente.

    See Also:
        ResolverStrategyRegistry: Elige la primera estrategia compatible.
    """
    @property
    def name(self) -> str:
        """Identifica la estrategia para evitar registros duplicados y explicar su selección.

        Returns:
            nombre estable del proveedor o política.
        """
        ...

    def supports(self, url: str) -> bool:
        """Decide si esta estrategia reconoce la URL de entrada.

        Args:
            url: URL o ruta del recurso que se interpreta.

        Returns:
            True si puede hacerse cargo de la resolución.
        """
        ...

    async def resolve(
        self,
        source_id: uuid.UUID,
        official_url: str,
        app: WinstallApp,
    ) -> ResolutionStatus:
        """Resuelve instaladores compatibles con la aplicación y actualiza la fuente indicada
        según la política del proveedor.

        Args:
            source_id: UUID de la fuente donde la estrategia debe guardar su resultado.
            official_url: Página oficial que selecciona la estrategia del proveedor.
            app: Datos Winstall de la aplicación para elegir instaladores compatibles.

        Returns:
            estado final de resolución de la fuente.
        """
        ...


@dataclass(frozen=True)
class CallbackResolverStrategy:
    """Adapta un predicado de URL y un callback existente al contrato de estrategia de
    resolución.

    Attributes:
        name: Nombre único de la estrategia.
        predicate: Comprobación de compatibilidad con la URL.
        callback: Operación asíncrona que resuelve y persiste la fuente.
    """

    name: str

    predicate: ResolverPredicate

    callback: ResolverCallback


    def supports(self, url: str) -> bool:
        """Delega la selección de proveedor en el predicado configurado.

        Args:
            url: URL o ruta del recurso que se interpreta.

        Returns:
            resultado del predicado.
        """
        return self.predicate(url)

    async def resolve(
        self,
        source_id: uuid.UUID,
        official_url: str,
        app: WinstallApp,
    ) -> ResolutionStatus:
        """Delega la fuente, URL y aplicación al callback configurado.

        Args:
            source_id: UUID de la fuente donde la estrategia debe guardar su resultado.
            official_url: Página oficial que selecciona la estrategia del proveedor.
            app: Datos Winstall de la aplicación para elegir instaladores compatibles.

        Returns:
            estado de resolución producido por el callback.
        """
        return await self.callback(source_id, official_url, app)


class ResolverStrategyRegistry:
    """Mantiene resolutores en orden de prioridad y prohíbe nombres duplicados para que la
    selección sea predecible.
    """
    def __init__(self, strategies: Iterable[ResolverStrategy] = ()) -> None:
        """Registra en orden las estrategias iniciales aplicando la misma comprobación de
        unicidad.

        Args:
            strategies: Estrategias iniciales en orden de prioridad.
        """
        self._strategies: list[ResolverStrategy] = []

        for strategy in strategies:
            self.register(strategy)

    @property
    def names(self) -> tuple[str, ...]:
        """Expone los nombres en el orden que se utiliza para buscar un resolutor.

        Returns:
            tupla de nombres registrados.
        """
        return tuple(strategy.name for strategy in self._strategies)

    def register(self, strategy: ResolverStrategy) -> None:
        """Añade una estrategia al final del orden de búsqueda si su nombre todavía no existe.

        Args:
            strategy: Estrategia que se añade al final del registro con nombre único.

        Raises:
            ValueError: resolver_strategy_already_registered si el nombre está duplicado.
        """
        if strategy.name in self.names:
            raise ValueError(f"resolver_strategy_already_registered:{strategy.name}")
        self._strategies.append(strategy)

    def find(self, url: str) -> ResolverStrategy | None:
        """Recorre estrategias en orden y escoge la primera cuyo predicado admite la URL.

        Args:
            url: URL o ruta del recurso que se interpreta.

        Returns:
            estrategia seleccionada o None.
        """
        return next((strategy for strategy in self._strategies if strategy.supports(url)), None)


@dataclass(frozen=True)
class CandidateResolverStrategy:
    """Adapta selección por proveedor y recopilación asíncrona de candidatos sin exigir
    publicación de fuentes.

    Attributes:
        name, predicate: Identidad única y condición de URL admitida.
        callback: Recopilador que recibe contexto de ejecución, aplicación y página oficial.
    """

    name: str

    predicate: ResolverPredicate

    callback: CandidateResolverCallback


    def supports(self, url: str) -> bool:
        """Evalúa si la URL pertenece al proveedor gestionado por este recopilador.

        Args:
            url: URL o ruta del recurso que se interpreta.

        Returns:
            resultado del predicado configurado.
        """
        return self.predicate(url)

    async def collect(
        self,
        runtime: ScrapeRuntime,
        app: WinstallApp,
        official_url: str,
    ) -> list[InstallerCandidate]:
        """Delega la recopilación con el contexto y aplicación actuales sin cambiar su orden ni
        puntuación.

        Args:
            runtime: Contexto de la ejecución actual que aporta al menos run_id.
            app: Datos Winstall de la aplicación para elegir instaladores compatibles.
            official_url: Página oficial que selecciona la estrategia del proveedor.

        Returns:
            candidatos producidos por el callback.
        """
        return await self.callback(runtime, app, official_url)


class CandidateResolverStrategyRegistry:
    """Mantiene recopiladores de proveedores en orden de preferencia y comprueba nombres únicos
    al registrarlos.
    """
    def __init__(self, strategies: Iterable[CandidateResolverStrategy] = ()) -> None:
        """Registra la secuencia inicial de recopiladores conservando su prioridad.

        Args:
            strategies: Estrategias iniciales en orden de prioridad.
        """
        self._strategies: list[CandidateResolverStrategy] = []

        for strategy in strategies:
            self.register(strategy)

    @property
    def names(self) -> tuple[str, ...]:
        """Expone los nombres de recopiladores en el orden de selección.

        Returns:
            tupla de nombres registrados.
        """
        return tuple(strategy.name for strategy in self._strategies)

    def register(self, strategy: CandidateResolverStrategy) -> None:
        """Añade un recopilador al final de la lista si su nombre es único.

        Args:
            strategy: Estrategia que se añade al final del registro con nombre único.

        Raises:
            ValueError: candidate_resolver_strategy_already_registered si el nombre ya existe.
        """
        if strategy.name in self.names:
            raise ValueError(f"candidate_resolver_strategy_already_registered:{strategy.name}")
        self._strategies.append(strategy)

    def find(self, url: str) -> CandidateResolverStrategy | None:
        """Selecciona el primer recopilador cuyo predicado admite la página oficial.

        Args:
            url: URL o ruta del recurso que se interpreta.

        Returns:
            estrategia de candidatos o None.
        """
        return next((strategy for strategy in self._strategies if strategy.supports(url)), None)
