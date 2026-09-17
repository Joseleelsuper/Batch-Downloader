"""Clasifica la confianza que puede comunicar la resolución interna de una fuente al worker."""
from __future__ import annotations

from collections.abc import Mapping
from datetime import datetime
from enum import StrEnum


class SourceTrustStatus(StrEnum):
    """Distingue validación vigente, evidencia atestiguada y ausencia de garantías suficientes
    para la descarga.

    Attributes:
        VERIFIED: Validación válida y vigente por una vía direct o fallback, sin confianza
            incompatible.
        ATTESTED: Metadatos que atestiguan el origen Winstall; esta marca tiene prioridad en
            la clasificación.
        UNRESOLVED: Falta una validación o vía admisible, la resolución caducó o la confianza
            explícita no es reconocida.

    See Also:
        source_trust_status: Aplica el orden de decisión de estas garantías.
    """
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
    """Prioriza evidencia atestiguada y, en su ausencia, exige validación válida, resolución
    directa o fallback y fecha futura.
    Una confianza explícita distinta de validated o verified impide clasificarla como
    verificada.

    Args:
        validation_status: Resultado persistido de validación de la fuente.
        resolution_status: Vía persistida de resolución, como direct o fallback.
        expires_at: Caducidad UTC sin tzinfo de la resolución.
        metadata: Evidencias de confianza y transporte guardadas con el artefacto.
        now: Instante UTC sin tzinfo contra el que se comprueba vigencia.

    Returns:
        categoría de confianza que corresponde a las evidencias recibidas.
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
