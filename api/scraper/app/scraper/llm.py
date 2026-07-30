"""Implementa las responsabilidades del módulo `llm`.
"""
from __future__ import annotations

import re
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol


class LLMProviderName(StrEnum):
    """Enumera los valores admitidos por `LLMProviderName`.
    """
    GROQ = "groq"
    """Constante que define `GROQ`.
    """
    DEEPSEEK = "deepseek"
    """Constante que define `DEEPSEEK`.
    """


@dataclass(frozen=True)
class LLMProviderConfig:
    """Define la configuración utilizada por `LLMProvider`.
    """

    name: LLMProviderName
    """Atributo de clase `name` de `LLMProviderConfig`.
    """
    api_key: str
    """Atributo de clase `api_key` de `LLMProviderConfig`.
    """
    base_url: str
    """Atributo de clase `base_url` de `LLMProviderConfig`.
    """
    model: str
    """Atributo de clase `model` de `LLMProviderConfig`.
    """

    @property
    def key(self) -> tuple[str, str]:
        """Ejecuta `key` dentro de `LLMProviderConfig`.

        Returns:
            tuple[str, str]: Resultado producido por la operación.
        """
        return (self.name.value, self.model)


@dataclass(frozen=True)
class ModelCooldown:
    """Representa el componente `ModelCooldown`.
    """
    provider: str
    """Atributo de clase `provider` de `ModelCooldown`.
    """
    model: str
    """Atributo de clase `model` de `ModelCooldown`.
    """
    reason: str
    """Atributo de clase `reason` de `ModelCooldown`.
    """
    remaining_seconds: float
    """Atributo de clase `remaining_seconds` de `ModelCooldown`.
    """


class ModelCooldownStore(Protocol):
    """Gestiona el almacenamiento de `ModelCooldown`.
    """
    def get(self, provider: LLMProviderConfig) -> ModelCooldown | None:
        """Ejecuta `get` dentro de `ModelCooldownStore`.

        Args:
            provider (LLMProviderConfig): Valor de `provider` utilizado por la operación.

        Returns:
            ModelCooldown | None: Resultado producido por la operación.
        """
        ...

    def start(
        self,
        provider: LLMProviderConfig,
        *,
        reason: str,
        seconds: float,
    ) -> None:
        """Ejecuta `start` dentro de `ModelCooldownStore`.

        Args:
            provider (LLMProviderConfig): Valor de `provider` utilizado por la operación.
            reason (str): Valor de `reason` utilizado por la operación.
            seconds (float): Valor de `seconds` utilizado por la operación.
        """
        ...


@dataclass(frozen=True)
class _CooldownEntry:
    """Representa el componente `_CooldownEntry`.
    """
    expires_at: float
    """Atributo de clase `expires_at` de `_CooldownEntry`.
    """
    reason: str
    """Atributo de clase `reason` de `_CooldownEntry`.
    """


class InMemoryModelCooldownStore:
    """Gestiona el almacenamiento de `InMemoryModelCooldown`.
    """

    def __init__(self, monotonic: Callable[[], float] = time.monotonic) -> None:
        """Inicializa una instancia de `InMemoryModelCooldownStore`.

        Args:
            monotonic (Callable[[], float]): Valor de `monotonic` utilizado por la operación.
        """
        self._monotonic = monotonic
        """Estado de instancia asociado a `_monotonic`.
        """
        self._entries: dict[tuple[str, str], _CooldownEntry] = {}
        """Estado de instancia asociado a `_entries`.
        """

    def get(self, provider: LLMProviderConfig) -> ModelCooldown | None:
        """Ejecuta `get` dentro de `InMemoryModelCooldownStore`.

        Args:
            provider (LLMProviderConfig): Valor de `provider` utilizado por la operación.

        Returns:
            ModelCooldown | None: Resultado producido por la operación.
        """
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
        """Ejecuta `start` dentro de `InMemoryModelCooldownStore`.

        Args:
            provider (LLMProviderConfig): Valor de `provider` utilizado por la operación.
            reason (str): Valor de `reason` utilizado por la operación.
            seconds (float): Valor de `seconds` utilizado por la operación.
        """
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
    """Representa un error relacionado con `LLMGeneration`.
    """
    def __init__(
        self,
        reason: str,
        provider: str | None = None,
        model: str | None = None,
        *,
        retryable: bool = False,
        cooldown_seconds: float | None = None,
    ) -> None:
        """Inicializa una instancia de `LLMGenerationError`.

        Args:
            reason (str): Valor de `reason` utilizado por la operación.
            provider (str | None): Valor de `provider` utilizado por la operación.
            model (str | None): Modelo utilizado por la operación.
            retryable (bool): Valor de `retryable` utilizado por la operación.
            cooldown_seconds (float | None): Valor de `cooldown_seconds` utilizado por la operación.
        """
        super().__init__(reason)
        self.reason = reason
        """Estado de instancia asociado a `reason`.
        """
        self.provider = provider
        """Estado de instancia asociado a `provider`.
        """
        self.model = model
        """Estado de instancia asociado a `model`.
        """
        self.retryable = retryable
        """Estado de instancia asociado a `retryable`.
        """
        self.cooldown_seconds = cooldown_seconds
        """Estado de instancia asociado a `cooldown_seconds`.
        """


class NoLLMProviderConfigured(LLMGenerationError):
    """Representa el componente `NoLLMProviderConfigured`.
    """
    pass


TRANSIENT_HTTP_STATUSES = frozenset({408, 425, 429, 500, 502, 503, 504})
"""Constante que define `TRANSIENT_HTTP_STATUSES`.
"""
_DURATION_TOKEN = re.compile(r"(?P<amount>\d+(?:\.\d+)?)(?P<unit>ms|s|m|h|d)", re.I)
"""Constante que define `_DURATION_TOKEN`.
"""


def unique_model_ids(primary: str, fallbacks: tuple[str | StrEnum, ...]) -> tuple[str, ...]:
    """Ejecuta la operación `unique_model_ids`.

    Args:
        primary (str): Valor de `primary` utilizado por la operación.
        fallbacks (tuple[str | StrEnum, ...]): Valor de `fallbacks` utilizado por la operación.

    Returns:
        tuple[str, ...]: Resultado producido por la operación.
    """

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
    """Ejecuta la operación `cooldown_from_headers`.

    Args:
        headers (Mapping[str, str]): Cabeceras HTTP utilizadas por la solicitud.
        default_seconds (float): Valor de `default_seconds` utilizado por la operación.

    Returns:
        float: Resultado producido por la operación.
    """

    normalized_headers = {key.lower(): value for key, value in headers.items()}
    values = [
        normalized_headers.get("retry-after"),
        normalized_headers.get("x-ratelimit-reset-requests"),
        normalized_headers.get("x-ratelimit-reset-tokens"),
    ]
    parsed = [seconds for value in values if (seconds := parse_duration_seconds(value))]
    return max(parsed, default=default_seconds)


def parse_duration_seconds(value: str | None) -> float | None:
    """Analiza la operación `duration_seconds`.

    Args:
        value (str | None): Valor que debe procesarse.

    Returns:
        float | None: Resultado producido por la operación.
    """
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
