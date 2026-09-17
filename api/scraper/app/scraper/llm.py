"""Define tipos y políticas puras compartidas por clientes LLM, cooldowns y parsing de
Retry-After.
"""
from __future__ import annotations

import re
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from enum import StrEnum
from typing import Protocol


class LLMProviderName(StrEnum):
    """Identifica los proveedores soportados por el servicio de descripciones."""
    GROQ = "groq"

    DEEPSEEK = "deepseek"



@dataclass(frozen=True)
class LLMProviderConfig:
    """Agrupa credencial, endpoint y modelo de una llamada LLM.

    Attributes:
        name: Proveedor.
        api_key: Credencial de API.
        base_url: Endpoint base.
        model: Identificador de modelo.
    """

    name: LLMProviderName

    api_key: str

    base_url: str

    model: str


    @property
    def key(self) -> tuple[str, str]:
        """Construye la clave estable de proveedor y modelo para cooldowns.

        Returns:
            tupla proveedor-modelo.
        """
        return (self.name.value, self.model)


@dataclass(frozen=True)
class ModelCooldown:
    """Expone un modelo temporalmente bloqueado y el tiempo restante.

    Attributes:
        provider: Proveedor.
        model: Modelo.
        reason: Causa del bloqueo.
        remaining_seconds: Segundos restantes.
    """
    provider: str

    model: str

    reason: str

    remaining_seconds: float



class ModelCooldownStore(Protocol):
    """Contrato de lectura e inicio de cooldown por proveedor y modelo."""
    def get(self, provider: LLMProviderConfig) -> ModelCooldown | None:
        """Devuelve un cooldown activo o None.

        Args:
            provider: Configuración del proveedor LLM.
        """
        ...

    def start(
        self,
        provider: LLMProviderConfig,
        *,
        reason: str,
        seconds: float,
    ) -> None:
        """Registra un bloqueo hasta que transcurra la duración indicada.

        Args:
            provider: Configuración del proveedor LLM.
            reason: Código de error o causa del cooldown.
            seconds: Duración del cooldown en segundos.
        """
        ...


@dataclass(frozen=True)
class _CooldownEntry:
    """Entrada interna con instante monotónico de expiración y causa."""
    expires_at: float

    reason: str



class InMemoryModelCooldownStore:
    """Implementación en memoria de cooldowns que elimina entradas caducadas al leerlas."""

    def __init__(self, monotonic: Callable[[], float] = time.monotonic) -> None:
        """Inicializa el almacén con reloj inyectable para pruebas deterministas.

        Args:
            monotonic: Reloj monotónico inyectable para pruebas.
        """
        self._monotonic = monotonic

        self._entries: dict[tuple[str, str], _CooldownEntry] = {}


    def get(self, provider: LLMProviderConfig) -> ModelCooldown | None:
        """Calcula tiempo restante y elimina un modelo cuando el cooldown terminó.

        Args:
            provider: Configuración del proveedor LLM.

        Returns:
            ModelCooldown o None.
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
        """Registra el vencimiento más lejano sin acortar un cooldown existente.

        Args:
            provider: Configuración del proveedor LLM.
            reason: Código de error o causa del cooldown.
            seconds: Duración del cooldown en segundos.
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
    """Error de generación con proveedor, modelo, posibilidad de reintento y cooldown sugerido.

    Attributes:
        reason: Código seguro.
        provider, model: Origen.
        retryable: Indica reintento.
        cooldown_seconds: Duración sugerida.
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
        """Conserva el contexto de un fallo LLM para la política de fallback.

        Args:
            reason: Código de error o causa del cooldown.
            provider: Configuración del proveedor LLM.
            model: Modelo LLM asociado al error, si se conoce.
            retryable: Indica si el consumidor puede reintentar.
            cooldown_seconds: Duración opcional que propone el error.
        """
        super().__init__(reason)
        self.reason = reason

        self.provider = provider

        self.model = model

        self.retryable = retryable

        self.cooldown_seconds = cooldown_seconds



class NoLLMProviderConfigured(LLMGenerationError):
    """Indica que ninguna credencial LLM está configurada."""
    pass


TRANSIENT_HTTP_STATUSES = frozenset({408, 425, 429, 500, 502, 503, 504})

_DURATION_TOKEN = re.compile(r"(?P<amount>\d+(?:\.\d+)?)(?P<unit>ms|s|m|h|d)", re.I)



def unique_model_ids(primary: str, fallbacks: tuple[str | StrEnum, ...]) -> tuple[str, ...]:
    """Combina modelo principal y fallbacks eliminando vacíos y duplicados.

    Args:
        primary: Modelo principal configurado.
        fallbacks: Modelos alternativos configurados.

    Returns:
        tupla de IDs en orden.
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
    """Interpreta Retry-After y cabeceras de reset en segundos y conserva el máximo.

    Args:
        headers: Cabeceras HTTP del proveedor.
        default_seconds: Espera utilizada si no hay una cabecera interpretable.

    Returns:
        espera calculada.
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
    """Acepta segundos decimales o expresiones ms/s/m/h/d y suma sus tokens.

    Args:
        value: Texto o tamaño que se interpreta.

    Returns:
        segundos o None.
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
