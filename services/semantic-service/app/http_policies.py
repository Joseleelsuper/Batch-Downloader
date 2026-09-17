"""Define dependencias HTTP de autenticación interna y admisión acotada de búsquedas."""
from __future__ import annotations

import asyncio
import secrets
from collections.abc import AsyncIterator
from typing import Annotated

from fastapi import Header, HTTPException

INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token"


class InternalServiceTokenGuard:
    """Rechaza credenciales internas ausentes o incorrectas comparándolas en tiempo constante.

    See Also:
        app.http_context: Construye el guarda compartido por los routers internos.
    """

    def __init__(self, expected_token: str) -> None:
        """Conserva el secreto esperado sin admitir un servicio configurado con token vacío.

        Args:
            expected_token: Secreto interno configurado; un valor vacío rechaza todas las
                peticiones.
        """
        self._expected_token = expected_token

    async def __call__(
        self,
        provided_token: Annotated[
            str | None,
            Header(alias=INTERNAL_SERVICE_TOKEN_HEADER),
        ] = None,
    ) -> None:
        """Valida X-Internal-Service-Token antes de ejecutar la operación interna.

        Args:
            provided_token: Cabecera recibida; una cabecera ausente se compara como cadena
                vacía.

        Raises:
            HTTPException: 401 si el secreto esperado está vacío o la cabecera no coincide.
        """
        matches = secrets.compare_digest(provided_token or "", self._expected_token)
        if not self._expected_token or not matches:
            raise HTTPException(status_code=401, detail={"code": "invalid_internal_token"})


class SearchCapacityGuard:
    """Limita las búsquedas admitidas mediante un semáforo con espera máxima y liberación
    garantizada.

    See Also:
        app.search_router.semantic_search: Operación que ocupa una plaza hasta finalizar la
            petición.
    """

    def __init__(self, slots: asyncio.Semaphore, wait_seconds: float) -> None:
        """Conserva semáforo y plazo de espera compartidos por las búsquedas del proceso.

        Args:
            slots: Semáforo del API que limita búsquedas simultáneas admitidas.
            wait_seconds: Tiempo máximo de espera para obtener una plaza, en segundos.
        """
        self._slots = slots
        self._wait_seconds = wait_seconds

    async def __call__(self) -> AsyncIterator[None]:
        """Reserva una plaza antes de la consulta y la devuelve al salir incluso si falla o se
        cancela.

        Yields:
            control de la petición mientras mantiene la plaza ocupada.

        Raises:
            HTTPException: 503 service_busy con Retry-After de un segundo si no hay plaza a
                tiempo.
        """
        try:
            await asyncio.wait_for(
                self._slots.acquire(),
                timeout=self._wait_seconds,
            )
        except TimeoutError as exception:
            raise HTTPException(
                status_code=503,
                detail={"code": "service_busy"},
                headers={"Retry-After": "1"},
            ) from exception
        try:
            yield
        finally:
            self._slots.release()
