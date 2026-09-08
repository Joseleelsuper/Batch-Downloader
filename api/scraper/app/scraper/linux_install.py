"""Materializa firmas públicas con el transporte SSRF existente; nunca instala software."""

from __future__ import annotations

import base64

from app.schemas.linux_install import approved_url
from app.scraper.http import FetchRequest, HttpxPublicResourceFetcher
from app.scraper.safe_http import SafeHttpError, validate_public_https_url


async def bundled_signature(profile: dict) -> str | None:
    verification = profile.get("verification") or {}
    url = verification.get("signatureUrl")
    if not url:
        return None
    hosts = (profile.get("update") or {}).get("allowedHosts", [])

    async def validate(value: str) -> str:
        if not approved_url(value, hosts):
            raise SafeHttpError("signature_origin_not_approved")
        return await validate_public_https_url(value)

    try:
        response = await HttpxPublicResourceFetcher(validate).fetch(
            FetchRequest(
                url=url,
                timeout=15,
                max_redirects=4,
                max_bytes=1024 * 1024,
                accept="application/pgp-signature, application/octet-stream",
            )
        )
        if response.status_code != 200 or not response.content:
            return None
        return base64.b64encode(response.content).decode("ascii")
    except SafeHttpError:
        # El binario permanece descargable, pero la ausencia de firma impide auto-instalar.
        return None
