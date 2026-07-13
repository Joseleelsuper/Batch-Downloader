from __future__ import annotations

import re
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol


class LLMProviderName(StrEnum):
    GROQ = "groq"
    DEEPSEEK = "deepseek"


@dataclass(frozen=True)
class LLMProviderConfig:
    """A single addressable model endpoint.

    Keeping the model in the configuration makes every attempt independently
    observable and lets the caller cool down only the quota that was exhausted.
    """

    name: LLMProviderName
    api_key: str
    base_url: str
    model: str

    @property
    def key(self) -> tuple[str, str]:
        return (self.name.value, self.model)


@dataclass(frozen=True)
class ModelCooldown:
    provider: str
    model: str
    reason: str
    remaining_seconds: float


class ModelCooldownStore(Protocol):
    def get(self, provider: LLMProviderConfig) -> ModelCooldown | None: ...

    def start(
        self,
        provider: LLMProviderConfig,
        *,
        reason: str,
        seconds: float,
    ) -> None: ...


@dataclass(frozen=True)
class _CooldownEntry:
    expires_at: float
    reason: str


class InMemoryModelCooldownStore:
    """Process-local cooldowns for rate-limited or temporarily unavailable models."""

    def __init__(self, monotonic: Callable[[], float] = time.monotonic) -> None:
        self._monotonic = monotonic
        self._entries: dict[tuple[str, str], _CooldownEntry] = {}

    def get(self, provider: LLMProviderConfig) -> ModelCooldown | None:
        entry = self._entries.get(provider.key)
        if entry is None:
            return None
        remaining = entry.expires_at - self._monotonic()
        if remaining <= 0:
            self._entries.pop(provider.key, None)
            return None
        return ModelCooldown(
            provider=provider.name.value,
            model=provider.model,
            reason=entry.reason,
            remaining_seconds=remaining,
        )

    def start(
        self,
        provider: LLMProviderConfig,
        *,
        reason: str,
        seconds: float,
    ) -> None:
        if seconds <= 0:
            return
        expires_at = self._monotonic() + seconds
        current = self._entries.get(provider.key)
        if current is None or current.expires_at < expires_at:
            self._entries[provider.key] = _CooldownEntry(
                expires_at=expires_at,
                reason=reason,
            )


class LLMGenerationError(Exception):
    def __init__(
        self,
        reason: str,
        provider: str | None = None,
        model: str | None = None,
        *,
        retryable: bool = False,
        cooldown_seconds: float | None = None,
    ) -> None:
        super().__init__(reason)
        self.reason = reason
        self.provider = provider
        self.model = model
        self.retryable = retryable
        self.cooldown_seconds = cooldown_seconds


class NoLLMProviderConfigured(LLMGenerationError):
    pass


TRANSIENT_HTTP_STATUSES = frozenset({408, 425, 429, 500, 502, 503, 504})
_DURATION_TOKEN = re.compile(r"(?P<amount>\d+(?:\.\d+)?)(?P<unit>ms|s|m|h|d)", re.I)


def unique_model_ids(primary: str, fallbacks: tuple[str | StrEnum, ...]) -> tuple[str, ...]:
    """Return a stable, non-empty model order without retrying duplicate IDs."""

    models: list[str] = []
    for value in (primary, *fallbacks):
        model = str(value).strip()
        if model and model not in models:
            models.append(model)
    return tuple(models)


def cooldown_from_headers(
    headers: Mapping[str, str],
    *,
    default_seconds: float,
) -> float:
    """Read Groq/OpenAI-compatible reset headers, falling back to a safe interval."""

    normalized_headers = {key.lower(): value for key, value in headers.items()}
    values = [
        normalized_headers.get("retry-after"),
        normalized_headers.get("x-ratelimit-reset-requests"),
        normalized_headers.get("x-ratelimit-reset-tokens"),
    ]
    parsed = [seconds for value in values if (seconds := parse_duration_seconds(value))]
    return max(parsed, default=default_seconds)


def parse_duration_seconds(value: str | None) -> float | None:
    if not value:
        return None
    normalized = value.strip().lower()
    try:
        return max(0.0, float(normalized))
    except ValueError:
        pass

    matches = list(_DURATION_TOKEN.finditer(normalized))
    if not matches:
        return None
    units = {"ms": 0.001, "s": 1.0, "m": 60.0, "h": 3600.0, "d": 86400.0}
    return sum(
        float(match.group("amount")) * units[match.group("unit").lower()]
        for match in matches
    )
