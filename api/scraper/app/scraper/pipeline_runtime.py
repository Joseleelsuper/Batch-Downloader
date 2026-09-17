"""Mantiene estado compartido, contadores y reintentos locales de la ejecución del scraper."""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import datetime

from sqlalchemy.exc import OperationalError
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
    """Reejecuta una operación con transacción limpia ante agotamiento del pool, deadlock o lock
    timeout de MySQL.

    Args:
        settings: Configuración del servicio y sus límites.
        component: Nombre del worker o componente para el diagnóstico.
        operation: Operación asíncrona que se reintentará en una sesión limpia.

    Returns:
        resultado de la operación.

    Raises:
        OperationalError: Si el error no es un lock transitorio o se agotan intentos.
    """
    for attempt in range(1, DATABASE_POOL_RETRY_ATTEMPTS + 1):
        try:
            return await operation()
        except (SQLAlchemyTimeoutError, OperationalError) as exc:
            if isinstance(exc, OperationalError):
                if not is_transient_mysql_lock_error(exc):
                    raise
                event = (
                    "scraper_claim_retry"
                    if component.startswith("claim:")
                    else "scraper_database_lock_retry"
                )
                error_code = mysql_error_code(exc)
            else:
                event = "scraper_database_pool_retry"
                error_code = None
            logger.warning(
                event,
                component=component,
                attempt=attempt,
                max_attempts=DATABASE_POOL_RETRY_ATTEMPTS,
                pool_size=settings.database_pool_max,
                timeout_seconds=settings.database_pool_timeout_seconds,
                mysql_error_code=error_code,
            )
            if attempt >= DATABASE_POOL_RETRY_ATTEMPTS:
                raise
            await asyncio.sleep(min(2.0, 0.25 * (2 ** min(attempt - 1, 3))))

    raise RuntimeError("database_pool_retry_exhausted")


def mysql_error_code(exc: OperationalError) -> int | None:
    """Extrae el código entero del error original cuando el driver lo proporciona.

    Args:
        exc: Excepción SQL o de tarea que se clasifica.

    Returns:
        código MySQL o None.
    """
    args: tuple[object, ...] = getattr(exc.orig, "args", ())
    return args[0] if args and isinstance(args[0], int) else None


def is_transient_mysql_lock_error(exc: OperationalError) -> bool:
    """Reconoce 1205 y 1213 como deadlock o lock timeout reintentable.

    Args:
        exc: Excepción SQL o de tarea que se clasifica.

    Returns:
        True si se puede repetir.
    """
    return mysql_error_code(exc) in {1205, 1213}


def async_session_local():
    """Importa de forma diferida la fábrica de sesiones para evitar ciclos durante el arranque.

    Returns:
        AsyncSessionLocal.
    """
    from app.db.session import AsyncSessionLocal

    return AsyncSessionLocal


@dataclass
class ScrapeCounters:
    """Contadores separados por descubrimiento, resolución, ausencia confirmada, revisión,
    omisión y fallo transitorio.
    """

    apps_discovered: int = 0

    apps_resolved: int = 0

    apps_failed: int = 0

    apps_skipped: int = 0

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
    """Contexto mutable compartido por workers, con eventos de coordinación y presupuestos
    protegidos por locks.
    """

    settings: Settings

    run_id: uuid.UUID

    run_started_at: datetime

    scope: ScrapeScope = ScrapeScope.INCREMENTAL
    """Scope inmutable asociado al manifest de la ejecución."""
    selected_app_ids: tuple[uuid.UUID, ...] = ()
    """UUID locales solicitados por el scope selected."""
    request_id: uuid.UUID | None = None
    """Solicitud durable que originó esta ejecución."""
    counters: ScrapeCounters = field(default_factory=ScrapeCounters)

    stop_event: asyncio.Event = field(default_factory=asyncio.Event)

    pause_event: asyncio.Event = field(default_factory=asyncio.Event)

    searcher_done: asyncio.Event = field(default_factory=asyncio.Event)

    filter_done: asyncio.Event = field(default_factory=asyncio.Event)

    scraper_done: asyncio.Event = field(default_factory=asyncio.Event)

    so_filter_done: asyncio.Event = field(default_factory=asyncio.Event)

    descriptor_done: asyncio.Event = field(default_factory=asyncio.Event)

    all_workers_done: asyncio.Event = field(default_factory=asyncio.Event)

    stopped_by_command: bool = False

    _counter_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    _descriptor_budget_lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    _descriptor_attempts: int = 0


    async def before_next_item(self) -> bool:
        """Espera mientras el run está pausado y permite avanzar solo si no se solicitó parada.

        Returns:
            True si el worker puede tomar otro trabajo.
        """
        while self.pause_event.is_set() and not self.stop_event.is_set():
            await asyncio.sleep(1)
        return not self.stop_event.is_set()

    async def increment(self, field_name: str, amount: int = 1) -> None:
        """Incrementa de forma atómica un contador del run.

        Args:
            field_name: Nombre de contador permitido en ScrapeCounters.
            amount: Incremento aplicado al contador.
        """
        async with self._counter_lock:
            setattr(self.counters, field_name, getattr(self.counters, field_name) + amount)

    async def reserve_descriptor_attempt(self) -> bool:
        """Reserva un intento de descriptor respetando llm_max_apps_per_run.

        Returns:
            True si queda presupuesto.
        """
        async with self._descriptor_budget_lock:
            maximum = self.settings.llm_max_apps_per_run
            if maximum > 0 and self._descriptor_attempts >= maximum:
                return False
            self._descriptor_attempts += 1
            return True

    async def release_descriptor_attempt(self) -> None:
        """Devuelve un intento reservado sin permitir que el contador sea negativo."""
        async with self._descriptor_budget_lock:
            self._descriptor_attempts = max(0, self._descriptor_attempts - 1)
