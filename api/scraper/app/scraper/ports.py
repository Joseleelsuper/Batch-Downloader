from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Protocol, TypeVar, runtime_checkable

from app.scraper.candidates import InstallerCandidate
from app.scraper.validator import ValidationResult

CatalogItem = TypeVar("CatalogItem")


@runtime_checkable
class CatalogProvider(Protocol[CatalogItem]):
    """Port implemented by external software-catalog adapters."""

    provider_name: str

    async def __aenter__(self) -> CatalogProvider[CatalogItem]: ...

    async def __aexit__(self, *args: object) -> None: ...

    def iter_apps(self) -> AsyncIterator[CatalogItem]: ...

    async def get_app(self, external_id: str) -> CatalogItem: ...


@runtime_checkable
class CandidateValidator(Protocol):
    async def validate(self, candidate: InstallerCandidate) -> ValidationResult: ...
