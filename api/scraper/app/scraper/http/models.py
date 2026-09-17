"""Define límites, respuesta acotada y clasificación de errores del transporte HTTP usado por
inspecciones y descubrimiento.
"""
from __future__ import annotations

from dataclasses import dataclass

import httpx


class SafeHttpError(Exception):
    """Propaga un código de fallo de transporte o de política de acceso y distingue si una nueva
    ejecución puede reintentarlo.

    Attributes:
        code: Motivo estable, como timeout, dns_not_public o content_too_large.
        transient: Marca de fallo recuperable que las rutas traducen normalmente a 503.

    See Also:
        app.scraper.http.fetchers.TransportErrorMappingExchange: Clasifica errores de red.
        app.scraper.safe_http: Aplica restricciones de URL antes del acceso.
    """

    def __init__(self, code: str, *, transient: bool = False) -> None:
        """Conserva código y recuperabilidad y utiliza el código como mensaje de la excepción.

        Args:
            code: Código estable del rechazo que pueden interpretar rutas y workers.
            transient: True permite tratar el fallo como recuperable; False expresa una
                restricción definitiva.
        """
        super().__init__(code)
        self.code = code
        self.transient = transient


@dataclass(frozen=True)
class SafeHttpResponse:
    """Entrega al llamador el resultado final ya leído y acotado, sin mantener una conexión HTTP
    abierta.

    Attributes:
        final_url, status_code: Destino tras redirecciones y estado HTTP final.
        content_type: MIME en minúsculas sin parámetros, o None cuando falta.
        content: Bytes del cuerpo dentro del límite de la petición.
        headers: Cabeceras finales recibidas.
    """

    final_url: str
    status_code: int
    content_type: str | None
    content: bytes
    headers: httpx.Headers


@dataclass(frozen=True)
class FetchRequest:
    """Describe la consulta de un recurso y sus límites antes de construir el cliente de red.

    Attributes:
        url: Destino inicial que se validará.
        timeout: Timeout HTTPX en segundos.
        max_redirects: Máximo de saltos permitidos después de la petición inicial.
        max_bytes: Máximo de bytes de cuerpo admitido.
        accept: Tipos aceptados enviados en la cabecera HTTP.
    """

    url: str
    timeout: float
    max_redirects: int
    max_bytes: int
    accept: str
