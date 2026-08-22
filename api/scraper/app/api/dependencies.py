"""Dependencias transversales compartidas por las rutas HTTP del scraper."""
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
    """Autoriza una ruta interna con comparación constante del secreto compartido."""
    expected_token = settings.internal_service_token.get_secret_value()
    token_matches = secrets.compare_digest(provided_token or "", expected_token)
    if not expected_token or not token_matches:
        raise HTTPException(status_code=401, detail={"code": "invalid_internal_token"})
