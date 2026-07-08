from enum import StrEnum


class AppStatus(StrEnum):
    ACTIVE = "active"
    DISABLED = "disabled"
    BROKEN = "broken"


class ResolutionStatus(StrEnum):
    DIRECT = "direct"
    FALLBACK = "fallback"
    REQUIRES_MANUAL_REVIEW = "requires_manual_review"
    MISSING = "missing"
    BROKEN = "broken"


class ValidationStatus(StrEnum):
    UNCHECKED = "unchecked"
    VALID = "valid"
    INVALID = "invalid"
    EXPIRED = "expired"


class ScrapeRunStatus(StrEnum):
    RUNNING = "running"
    COMPLETED = "completed"
    PARTIAL = "partial"
    FAILED = "failed"


class LongDescriptionStatus(StrEnum):
    PENDING = "pending"
    COMPLETED = "completed"
    FAILED = "failed"
    SKIPPED = "skipped"
