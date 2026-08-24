"""Estado compartido y reintentos locales del pipeline del scraper."""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy.exc import TimeoutError as SQLAlchemyTimeoutError

from app.core.config import Settings
from app.core.logging import get_logger
from app.db.enums import ScrapeScope

logger = get_logger(__name__)

DATABASE_POOL_RETRY_ATTEMPTS = 12


async def retry_database_pool_operation[DatabaseResult](
    settings: Settings,
    component: str,
    operation: Callable[[], Awaitable[DatabaseResult]],
) -> DatabaseResult:
    """Reintenta exclusivamente la contención local del pool de conexiones.

    Un ``SQLAlchemyTimeoutError`` en este servicio significa que todas las conexiones
    acotadas del proceso están ocupadas. No equivale a un fallo del proveedor ni debe
    degradar una aplicación. Los errores de red o SQL reales siguen propagándose.
    """
    for attempt in range(1, DATABASE_POOL_RETRY_ATTEMPTS + 1):
        try:
            return await operation()
        except SQLAlchemyTimeoutError:
            logger.warning(
                "scraper_database_pool_retry",
                component=component,
                attempt=attempt,
                max_attempts=DATABASE_POOL_RETRY_ATTEMPTS,
                pool_size=settings.database_pool_max,
                timeout_seconds=settings.database_pool_timeout_seconds,
            )
            if attempt >= DATABASE_POOL_RETRY_ATTEMPTS:
                raise
            await asyncio.sleep(min(2.0, 0.25 * (2 ** min(attempt - 1, 3))))

    raise RuntimeError("database_pool_retry_exhausted")


def async_session_local():
    """Ejecuta la operación `async_session_local`."""
    from app.db.session import AsyncSessionLocal

    return AsyncSessionLocal


@dataclass
class ScrapeCounters:
    """Representa el componente `ScrapeCounters`."""

    apps_discovered: int = 0
    """Atributo de clase `apps_discovered` de `ScrapeCounters`.
    """
    apps_resolved: int = 0
    """Atributo de clase `apps_resolved` de `ScrapeCounters`.
    """
    apps_failed: int = 0
    """Atributo de clase `apps_failed` de `ScrapeCounters`.
    """
    apps_skipped: int = 0
    """Atributo de clase `apps_skipped` de `ScrapeCounters`.
    """
    apps_confirmed_missing: int = 0
    """Ausencias con evidencia activa que no convierten la ejecución en parcial."""
    apps_needs_review: int = 0
    """Casos sin evidencia suficiente para confirmar una ausencia."""
    apps_transient_failed: int = 0
    """Fallos recuperables que preservan fuentes y estado previos."""
    apps_skipped_unchanged: int = 0
    """Aplicaciones disponibles cuyo fingerprint no ha cambiado."""


@dataclass
class PipelineRuntime:
    """Mantiene el estado de ejecución de `Pipeline`."""

    settings: Settings
    """Atributo de clase `settings` de `PipelineRuntime`.
    """
    run_id: uuid.UUID
    """Atributo de clase `run_id` de `PipelineRuntime`.
    """
    run_started_at: datetime
    """Atributo de clase `run_started_at` de `PipelineRuntime`.
    """
    scope: ScrapeScope = ScrapeScope.INCREMENTAL
    """Scope inmutable asociado al manifest de la ejecución."""
    selected_app_ids: tuple[uuid.UUID, ...] = ()
    """UUID locales solicitados por el scope selected."""
    request_id: uuid.UUID | None = None
    """Solicitud durable que originó esta ejecución."""
    counters: ScrapeCounters = field(default_factory=ScrapeCounters)
    """Atributo de clase `counters` de `PipelineRuntime`.
    """
    stop_event: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `stop_event` de `PipelineRuntime`.
    """
    pause_event: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `pause_event` de `PipelineRuntime`.
    """
    searcher_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `searcher_done` de `PipelineRuntime`.
    """
    filter_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `filter_done` de `PipelineRuntime`.
    """
    scraper_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `scraper_done` de `PipelineRuntime`.
    """
    so_filter_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `so_filter_done` de `PipelineRuntime`.
    """
    descriptor_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `descriptor_done` de `PipelineRuntime`.
    """
    all_workers_done: asyncio.Event = field(default_factory=asyncio.Event)
    """Atributo de clase `all_workers_done` de `PipelineRuntime`.
    """
    stopped_by_command: bool = False
    """Atributo de clase `stopped_by_command` de `PipelineRuntime`.
    """
    _counter_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    """Atributo de clase `_counter_lock` de `PipelineRuntime`.
    """
    _descriptor_budget_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    """Atributo de clase `_descriptor_budget_lock` de `PipelineRuntime`.
    """
    _descriptor_attempts: int = 0
    """Atributo de clase `_descriptor_attempts` de `PipelineRuntime`.
    """

    async def before_next_item(self) -> bool:
        """Ejecuta `before_next_item` dentro de `PipelineRuntime`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        while self.pause_event.is_set() and not self.stop_event.is_set():
            await asyncio.sleep(1)
        return not self.stop_event.is_set()

    async def increment(self, field_name: str, amount: int = 1) -> None:
        """Ejecuta `increment` dentro de `PipelineRuntime`.

        Args:
            field_name (str): Valor de `field_name` utilizado por la operación.
            amount (int): Valor de `amount` utilizado por la operación.
        """
        async with self._counter_lock:
            setattr(self.counters, field_name, getattr(self.counters, field_name) + amount)

    async def reserve_descriptor_attempt(self) -> bool:
        """Ejecuta `reserve_descriptor_attempt` dentro de `PipelineRuntime`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        async with self._descriptor_budget_lock:
            maximum = self.settings.llm_max_apps_per_run
            if maximum > 0 and self._descriptor_attempts >= maximum:
                return False
            self._descriptor_attempts += 1
            return True

    async def release_descriptor_attempt(self) -> None:
        """Libera la operación `descriptor_attempt`."""
        async with self._descriptor_budget_lock:
            self._descriptor_attempts = max(0, self._descriptor_attempts - 1)
