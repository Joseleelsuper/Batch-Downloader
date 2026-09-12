"""Separa intercambio de un salto, validación de URL y consulta completa para componer políticas
sin acoplar casos de uso a HTTPX.
"""
from __future__ import annotations

from collections.abc import Awaitable, Callable
from contextlib import AbstractAsyncContextManager
from typing import Protocol

import httpx

from app.scraper.http.models import FetchRequest, SafeHttpResponse

UrlValidator = Callable[[str], Awaitable[str]]


class SingleHopExchange(Protocol):
    """Permite abrir una respuesta de un único destino dentro de un contexto asíncrono que
    delimita la conexión.

    See Also:
        app.scraper.http.fetchers.RedirectFollowingFetcher: Coordina cada redirección mediante
            este contrato.
    """

    def stream(self, url: str) -> AbstractAsyncContextManager[httpx.Response]:
        """Abre una respuesta en streaming para la URL recibida; el contexto del llamador
        controla cuándo se cierra.

        Args:
            url: URL completa que se solicita o valida antes de abrir la conexión.

        Returns:
            contexto asíncrono de la respuesta de un salto.
        """
        ...


class HttpFetcher(Protocol):
    """Obtiene un recurso completo bajo los límites declarados en una petición.

    See Also:
        app.scraper.http.models.FetchRequest: Define límites de la consulta.
        app.scraper.http.models.SafeHttpResponse: Devuelve el cuerpo acotado y sus metadatos.
    """

    async def fetch(self, request: FetchRequest) -> SafeHttpResponse:
        """Resuelve la consulta, incluidas sus redirecciones, y devuelve el cuerpo dentro de los
        límites contratados.

        Args:
            request: URL, límites de tiempo y bytes, saltos máximos y cabecera Accept de la
                consulta.

        Returns:
            respuesta final con bytes y metadatos.

        Raises:
            app.scraper.http.models.SafeHttpError: Si el transporte o una política impiden
                completar la consulta.
        """
        ...
