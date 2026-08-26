"""Puertos pequeños para componer el acceso HTTP externo."""
from __future__ import annotations

from collections.abc import Awaitable, Callable
from contextlib import AbstractAsyncContextManager
from typing import Protocol

import httpx

from app.scraper.http.models import FetchRequest, SafeHttpResponse

UrlValidator = Callable[[str], Awaitable[str]]


class SingleHopExchange(Protocol):
    """Abre exactamente un salto HTTP y deja el lifecycle al consumidor."""

    def stream(self, url: str) -> AbstractAsyncContextManager[httpx.Response]:
        """Abre una respuesta en streaming sin seguir redirecciones."""
        ...


class HttpFetcher(Protocol):
    """Recupera un recurso aplicando las políticas compuestas."""

    async def fetch(self, request: FetchRequest) -> SafeHttpResponse:
        """Recupera el recurso solicitado."""
        ...
