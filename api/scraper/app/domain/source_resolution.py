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
    confidence = str(metadata.get("validation_confidence") or "").lower()
    if confidence == "attested" or metadata.get("transport_security") == (
        "https_winstall_edge_attested"
    ):
        return SourceTrustStatus.ATTESTED
    if (
        validation_status != "valid"
        or resolution_status not in {"direct", "fallback"}
        or expires_at <= now
    ):
        return SourceTrustStatus.UNRESOLVED
    if confidence and confidence not in {"validated", "verified"}:
        return SourceTrustStatus.UNRESOLVED
    # Rows created before confidence became explicit were only persisted after
    # successful binary validation, except for the edge-attested marker above.
    return SourceTrustStatus.VERIFIED
