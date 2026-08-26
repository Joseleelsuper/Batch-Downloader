"""Contratos inmutables del pipeline HTTP seguro."""
from __future__ import annotations

from dataclasses import dataclass

import httpx


class SafeHttpError(Exception):
    """Fallo público y saneado de una política o del transporte HTTP."""

    def __init__(self, code: str, *, transient: bool = False) -> None:
        super().__init__(code)
        self.code = code
        self.transient = transient


@dataclass(frozen=True)
class SafeHttpResponse:
    """Respuesta remota materializada dentro del límite configurado."""

    final_url: str
    status_code: int
    content_type: str | None
    content: bytes
    headers: httpx.Headers


@dataclass(frozen=True)
class FetchRequest:
    """Parámetros funcionales y límites de una recuperación pública."""

    url: str
    timeout: float
    max_redirects: int
    max_bytes: int
    accept: str
