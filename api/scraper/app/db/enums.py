"""Implementa las responsabilidades del módulo `enums`.
"""
from enum import StrEnum


class AppStatus(StrEnum):
    """Enumera los valores admitidos por `AppStatus`.
    """
    ACTIVE = "active"
    """Constante que define `ACTIVE`.
    """
    DISABLED = "disabled"
    """Constante que define `DISABLED`.
    """
    BROKEN = "broken"
    """Constante que define `BROKEN`.
    """


class ResolutionStatus(StrEnum):
    """Enumera los valores admitidos por `ResolutionStatus`.
    """
    DIRECT = "direct"
    """Constante que define `DIRECT`.
    """
    FALLBACK = "fallback"
    """Constante que define `FALLBACK`.
    """
    REQUIRES_MANUAL_REVIEW = "requires_manual_review"
    """Constante que define `REQUIRES_MANUAL_REVIEW`.
    """
    MISSING = "missing"
    """Constante que define `MISSING`.
    """
    BROKEN = "broken"
    """Constante que define `BROKEN`.
    """


class ValidationStatus(StrEnum):
    """Enumera los valores admitidos por `ValidationStatus`.
    """
    UNCHECKED = "unchecked"
    """Constante que define `UNCHECKED`.
    """
    VALID = "valid"
    """Constante que define `VALID`.
    """
    INVALID = "invalid"
    """Constante que define `INVALID`.
    """
    EXPIRED = "expired"
    """Constante que define `EXPIRED`.
    """


class ScrapeRunStatus(StrEnum):
    """Enumera los valores admitidos por `ScrapeRunStatus`.
    """
    RUNNING = "running"
    """Constante que define `RUNNING`.
    """
    COMPLETED = "completed"
    """Constante que define `COMPLETED`.
    """
    PARTIAL = "partial"
    """Constante que define `PARTIAL`.
    """
    FAILED = "failed"
    """Constante que define `FAILED`.
    """


class ScrapeScope(StrEnum):
    """Define el conjunto estable que debe procesar una solicitud de scraping."""

    INCREMENTAL = "incremental"
    UNRESOLVED = "unresolved"
    SELECTED = "selected"
    FULL = "full"


class ScrapeOutcome(StrEnum):
    """Clasifica el resultado por aplicación sin confundir ausencia y fallo temporal."""

    RESOLVED = "resolved"
    CONFIRMED_MISSING = "confirmed_missing"
    NEEDS_REVIEW = "needs_review"
    TRANSIENT_FAILED = "transient_failed"
    SKIPPED_UNCHANGED = "skipped_unchanged"


class AbsenceVerificationStatus(StrEnum):
    """Estados del acta durable que acredita una ausencia de instalador."""

    ACTIVE = "active"
    INVALIDATED = "invalidated"
    SUPERSEDED = "superseded"


class LongDescriptionStatus(StrEnum):
    """Enumera los valores admitidos por `LongDescriptionStatus`.
    """
    PENDING = "pending"
    """Constante que define `PENDING`.
    """
    COMPLETED = "completed"
    """Constante que define `COMPLETED`.
    """
    FAILED = "failed"
    """Constante que define `FAILED`.
    """
    SKIPPED = "skipped"
    """Constante que define `SKIPPED`.
    """
