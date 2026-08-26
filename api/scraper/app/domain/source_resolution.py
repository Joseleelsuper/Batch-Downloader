"""Implementa las responsabilidades del módulo `source_resolution`.
"""
from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime
from enum import StrEnum


class SourceTrustStatus(StrEnum):
    """Enumera los valores admitidos por `SourceTrustStatus`.
    """
    VERIFIED = "VERIFIED"
    """Constante que define `VERIFIED`.
    """
    ATTESTED = "ATTESTED"
    """Constante que define `ATTESTED`.
    """
    UNRESOLVED = "UNRESOLVED"
    """Constante que define `UNRESOLVED`.
    """


def source_trust_status(
    *,
    validation_status: str,
    resolution_status: str,
    expires_at: datetime,
    metadata: Mapping[str, object],
    now: datetime,
) -> SourceTrustStatus:
    """Ejecuta la operación `source_trust_status`.

    Args:
        validation_status (str): Valor de `validation_status` utilizado por la operación.
        resolution_status (str): Valor de `resolution_status` utilizado por la operación.
        expires_at (datetime): Instante asociado a `expires`.
        metadata (Mapping[str, object]): Valor de `metadata` utilizado por la operación.
        now (datetime): Valor de `now` utilizado por la operación.

    Returns:
        SourceTrustStatus: Resultado producido por la operación.
    """
    confidence = str(metadata.get("validation_confidence") or "").lower()
    if confidence == "attested" or metadata.get("transport_security") in {
        "https_winstall_edge_attested",
        "http_winstall_verified",
    }:
        return SourceTrustStatus.ATTESTED
    if (
        validation_status != "valid"
        or resolution_status not in {"direct", "fallback"}
        or expires_at <= now
    ):
        return SourceTrustStatus.UNRESOLVED
    if confidence and confidence not in {"validated", "verified"}:
        return SourceTrustStatus.UNRESOLVED
# Las filas creadas antes de explicitar la confianza solo se persistieron después
# de una validación binaria correcta, salvo el antiguo marcador atestiguado en el perímetro.
    return SourceTrustStatus.VERIFIED
