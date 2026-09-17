"""Define los contratos que permiten sustituir proveedores de catálogo y validadores de
candidatos por composición.
"""
from __future__ import annotations

from collections.abc import AsyncIterator
from typing import Protocol, TypeVar, runtime_checkable

from app.scraper.candidates import InstallerCandidate
from app.scraper.validator import ValidationResult

CatalogItem = TypeVar("CatalogItem")



@runtime_checkable
class CatalogProvider(Protocol[CatalogItem]):
    """Ofrece iteración de catálogo y consulta de detalle dentro de un contexto asíncrono que
    controla conexiones del proveedor.

    Attributes:
        provider_name: Identificador de origen utilizado al registrar el progreso.
    """

    provider_name: str


    async def __aenter__(self) -> CatalogProvider[CatalogItem]:
        """Abre los recursos del proveedor para iterar aplicaciones o pedir detalles.

        Returns:
            proveedor preparado para las consultas.
        """
        ...

    async def __aexit__(self, *args: object) -> None:
        """Cierra los recursos del proveedor al abandonar el contexto, incluso tras una
        excepción.

        Args:
            args: Información de salida del contexto asíncrono: tipo, instancia y traza de
                excepción si existe.
        """
        ...

    def iter_apps(self) -> AsyncIterator[CatalogItem]:
        """Recorre aplicaciones del proveedor sin exigir cargar todo el catálogo en memoria.

        Returns:
            iterador asíncrono de resúmenes de catálogo.
        """
        ...

    async def get_app(self, external_id: str) -> CatalogItem:
        """Recupera el detalle de la aplicación identificada dentro del proveedor.

        Args:
            external_id: Identidad del paquete dentro del proveedor.

        Returns:
            datos completos disponibles para resolver instaladores.
        """
        ...


@runtime_checkable
class CandidateValidator(Protocol):
    """Comprueba un candidato y devuelve evidencia técnica y confianza sin persistir una fuente
    del catálogo.

    See Also:
        app.scraper.validator.ValidationResult: Separa éxito, rechazo y confianza de la
            comprobación.
    """
    async def validate(self, candidate: InstallerCandidate) -> ValidationResult:
        """Evalúa el destino del candidato y la evidencia disponible de instalador.

        Args:
            candidate: Candidato de instalador que debe comprobarse.

        Returns:
            resultado de validación con metadatos y motivo cuando se rechaza.
        """
        ...
