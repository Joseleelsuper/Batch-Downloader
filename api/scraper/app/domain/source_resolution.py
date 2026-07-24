from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime
from enum import StrEnum


class SourceTrustStatus(StrEnum):
    VERIFIED = "VERIFIED"
    ATTESTED = "ATTESTED"
    UNRESOLVED = "UNRESOLVED"


def source_trust_status(
    *,
    validation_status: str,
    resolution_status: str,
    expires_at: datetime,
    metadata: Mapping[str, object],
    now: datetime,
) -> SourceTrustStatus:
    """Determina la confianza según los estados de validación y resolución.

    Args:
        validation_status (str): El estado de validación de la fuente.
        resolution_status (str): El estado de resolución de la fuente.
        expires_at (datetime): La fecha de vencimiento de la fuente.
        metadata (Mapping[str, object]): Los metadatos de la fuente.
        now (datetime): La fecha y hora actuales.

    Returns:
        SourceTrustStatus: El estado de confianza de la fuente.
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
    # Las filas creadas antes de que la confianza fuera explícita solo se persistieron después
    # de una validación binaria exitosa, excepto por el marcador edge-attested anterior.
    return SourceTrustStatus.VERIFIED
