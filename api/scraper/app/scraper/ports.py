"""Implementa las responsabilidades del módulo `ports`.
"""
from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Protocol, TypeVar, runtime_checkable

from app.scraper.candidates import InstallerCandidate
from app.scraper.validator import ValidationResult

CatalogItem = TypeVar("CatalogItem")
"""Estado global asociado a `CatalogItem`.
"""


@runtime_checkable
class CatalogProvider(Protocol[CatalogItem]):
    """Representa el componente `CatalogProvider`.
    """

    provider_name: str
    """Atributo de clase `provider_name` de `CatalogProvider`.
    """

    async def __aenter__(self) -> CatalogProvider[CatalogItem]:
        """Abre el contexto asíncrono y devuelve la instancia preparada.

        Returns:
            CatalogProvider[CatalogItem]: Resultado producido por la operación.
        """
        ...

    async def __aexit__(self, *args: object) -> None:
        """Cierra el contexto asíncrono y libera sus recursos.

        Args:
            *args (object): Valor de `args` utilizado por la operación.
        """
        ...

    def iter_apps(self) -> AsyncIterator[CatalogItem]:
        """Ejecuta `iter_apps` dentro de `CatalogProvider`.

        Returns:
            AsyncIterator[CatalogItem]: Resultado producido por la operación.
        """
        ...

    async def get_app(self, external_id: str) -> CatalogItem:
        """Obtiene la operación `app`.

        Args:
            external_id (str): Identificador de `external` utilizado por la operación.

        Returns:
            CatalogItem: Resultado de `get_app`.
        """
        ...


@runtime_checkable
class CandidateValidator(Protocol):
    """Representa el componente `CandidateValidator`.
    """
    async def validate(self, candidate: InstallerCandidate) -> ValidationResult:
        """Ejecuta `validate` dentro de `CandidateValidator`.

        Args:
            candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

        Returns:
            ValidationResult: Resultado producido por la operación.
        """
        ...
