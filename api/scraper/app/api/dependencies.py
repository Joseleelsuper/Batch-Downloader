"""Centraliza la autenticación compartida por las rutas internas del scraper."""
from __future__ import annotations

import secrets
from typing import Annotated

from fastapi import Depends, Header, HTTPException

from app.core.config import Settings, get_settings

INTERNAL_SERVICE_TOKEN_HEADER = "X-Internal-Service-Token"


async def require_internal_service_token(
    settings: Annotated[Settings, Depends(get_settings)],
    provided_token: Annotated[
        str | None,
        Header(alias=INTERNAL_SERVICE_TOKEN_HEADER),
    ] = None,
) -> None:
    """Compara el secreto recibido en tiempo constante y rechaza también una configuración cuyo
    secreto esperado esté vacío.

    Args:
        settings: Configuración del servicio que aporta el secreto interno esperado.
        provided_token: Valor de X-Internal-Service-Token; None se compara como cadena vacía.

    Raises:
        fastapi.HTTPException: 401 con invalid_internal_token si el secreto no coincide o no
            está configurado.

    See Also:
        app.api.internal_routes: Protege coordinación y resolución internas.
        app.api.linux_install_routes: Protege la administración de perfiles Linux.
    """
    expected_token = settings.internal_service_token.get_secret_value()
    token_matches = secrets.compare_digest(provided_token or "", expected_token)
    if not expected_token or not token_matches:
        raise HTTPException(status_code=401, detail={"code": "invalid_internal_token"})
