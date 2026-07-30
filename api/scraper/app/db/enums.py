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
