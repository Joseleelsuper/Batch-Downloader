"""Operaciones auxiliares compartidas por los workers del pipeline."""

from __future__ import annotations

import asyncio
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy.exc import OperationalError, StatementError

from app.core.config import Settings
from app.core.json_safe import json_safe
from app.core.logging import get_logger
from app.db.enums import ScrapeOutcome
from app.db.models import ScraperWorkItem
from app.repositories.pipeline import PipelineRepository
from app.repositories.runs import ScrapeRunRepository
from app.scraper.pipeline_runtime import async_session_local, retry_database_pool_operation
from app.scraper.winstall import WinstallApp, parse_winstall_app

logger = get_logger(__name__)


async def claim_item(
    settings: Settings,
    queue: str,
    worker_id_value: str,
    *,
    run_id: uuid.UUID | None = None,
) -> ScraperWorkItem | None:
    """Reserva la operación `item`.

    Args:
        settings (Settings): Configuración del servicio.
        queue (str): Valor de `queue` utilizado por la operación.
        worker_id_value (str): Valor de `worker_id_value` utilizado por la operación.

    Returns:
        ScraperWorkItem | None: Resultado producido por la operación.
    """

    async def claim() -> ScraperWorkItem | None:
        async with async_session_local()() as session:
            pipeline = PipelineRepository(session)
            item = await pipeline.claim_next(
                queue,
                worker_id=worker_id_value,
                lease_seconds=max(60, int(settings.scrape_app_timeout_seconds * 2)),
                run_id=run_id,
            )
            depth = await pipeline.queue_depth(queue, run_id=run_id)
            await session.commit()
            if item:
                logger.info(
                    "scraper_pipeline_item_claimed",
                    queue=queue,
                    winstall_id=item.package_id,
                    worker_id=worker_id_value,
                    attempts=item.attempts,
                    depth=depth,
                )
            return item

    return await retry_database_pool_operation(
        settings,
        f"claim:{queue}",
        claim,
    )


async def queue_has_active_work(
    settings: Settings,
    queue: str,
    run_id: uuid.UUID,
) -> bool:
    """Comprueba trabajo queued/in_progress aunque aún no sea reclamable."""

    async def check() -> bool:
        async with async_session_local()() as session:
            depth = await PipelineRepository(session).queue_depth(queue, run_id=run_id)
            return depth > 0

    return await retry_database_pool_operation(
        settings,
        f"drain:{queue}",
        check,
    )


async def finish_item(
    settings: Settings,
    item: ScraperWorkItem,
    action: str,
    message: str | None,
    *,
    delay_seconds: int = 2,
) -> None:
    """Ejecuta la operación `finish_item`.

    Args:
        settings (Settings): Configuración del servicio.
        item (ScraperWorkItem): Valor de `item` utilizado por la operación.
        action (str): Valor de `action` utilizado por la operación.
        message (str | None): Mensaje que debe procesarse.
        delay_seconds (int): Valor de `delay_seconds` utilizado por la operación.
    """

    async def finish() -> None:
        async with async_session_local()() as session:
            pipeline = PipelineRepository(session)
            db_item = await session.get(ScraperWorkItem, item.id)
            if not db_item:
                return
            if action == "complete":
                await pipeline.complete(db_item)
            elif action == "discard":
                await pipeline.discard(db_item, message or "discarded")
            elif action == "requeue":
                await pipeline.requeue(
                    db_item,
                    message or "retry",
                    delay_seconds=delay_seconds,
                )
            else:
                await pipeline.fail(db_item, message or "failed")
            depth = await pipeline.queue_depth(db_item.queue, run_id=db_item.run_id)
            await session.commit()
            logger.info(
                "scraper_pipeline_item_finished",
                queue=db_item.queue,
                winstall_id=db_item.package_id,
                action=action,
                reason=message,
                depth=depth,
            )

    await retry_database_pool_operation(
        settings,
        f"finish:{item.queue}",
        finish,
    )


async def set_current(
    settings: Settings,
    run_id: uuid.UUID,
    package_id: str | None,
    app_name: str | None,
    phase: str,
) -> None:
    """Establece la operación `current`.

    Args:
        settings (Settings): Configuración del servicio.
        run_id (uuid.UUID): Identificador de `run` utilizado por la operación.
        package_id (str | None): Identificador de `package` utilizado por la operación.
        app_name (str | None): Valor de `app_name` utilizado por la operación.
        phase (str): Valor de `phase` utilizado por la operación.
    """

    async def persist() -> None:
        async with async_session_local()() as session:
            runs = ScrapeRunRepository(session, settings)
            await runs.set_current(run_id, package_id, app_name, phase)
            await session.commit()

    await retry_database_pool_operation(
        settings,
        "run_set_current",
        persist,
    )


def parse_payload_app(payload: dict[str, Any], fallback_package_id: str) -> WinstallApp:
    """Analiza la operación `payload_app`.

    Args:
        payload (dict[str, Any]): Carga de datos recibida por la operación.
        fallback_package_id (str): Identificador de `fallback_package` utilizado por la operación.

    Returns:
        WinstallApp: Resultado producido por la operación.
    """
    raw = payload.get("app")
    if isinstance(raw, dict):
        return parse_winstall_app(raw)
    return parse_winstall_app({"_id": fallback_package_id, "name": fallback_package_id})


def payload_package_id(payload: dict[str, Any], item: ScraperWorkItem) -> str:
    """Ejecuta la operación `payload_package_id`.

    Args:
        payload (dict[str, Any]): Carga de datos recibida por la operación.
        item (ScraperWorkItem): Valor de `item` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    value = payload.get("package_id") or item.package_id
    return str(value)


def provider_snapshot_absence_outcome(
    has_active_verification: bool,
) -> ScrapeOutcome:
    """Distingue una ausencia acreditada de un caso que aún requiere revisión."""
    return (
        ScrapeOutcome.CONFIRMED_MISSING if has_active_verification else ScrapeOutcome.NEEDS_REVIEW
    )


def is_stale_control_command(command: Any, run_started_at: datetime) -> bool:
    """Indica si se cumple la operación `stale_control_command`.

    Args:
        command (Any): Comando que debe procesarse.
        run_started_at (datetime): Instante asociado a `run_started`.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return (
        command.command in {"pause", "resume", "stop", "force_stop"}
        and command.created_at < run_started_at
    )


def first_task_failure(error: BaseException) -> BaseException:
    """Ejecuta la operación `first_task_failure`.

    Args:
        error (BaseException): Error que debe registrarse o propagarse.

    Returns:
        BaseException: Resultado producido por la operación.
    """

    if isinstance(error, BaseExceptionGroup):
        for nested in error.exceptions:
            failure = first_task_failure(nested)
            if not isinstance(failure, asyncio.CancelledError):
                return failure
    return error


def scrape_app_failure_metadata(exc: Exception, winstall_id: str) -> dict:
    """Ejecuta la operación `scrape_app_failure_metadata`.

    Args:
        exc (Exception): Valor de `exc` utilizado por la operación.
        winstall_id (str): Identificador de `winstall` utilizado por la operación.

    Returns:
        dict: Mapa con los datos producidos por la operación.
    """
    metadata: dict[str, object] = {
        "winstall_id": winstall_id,
        "error": exc.__class__.__name__,
        "detail": exception_detail(exc),
    }
    if isinstance(exc, StatementError):
        metadata["statement"] = truncate_text(exc.statement, 1200)
        metadata["params"] = truncate_text(repr(exc.params), 1200)
    return json_safe(metadata)


def exception_detail(exc: Exception) -> str:
    """Ejecuta la operación `exception_detail`.

    Args:
        exc (Exception): Valor de `exc` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    if isinstance(exc, StatementError) and exc.orig is not None:
        return truncate_text(f"{exc.orig.__class__.__name__}: {exc.orig}", 1200) or ""
    return truncate_text(str(exc), 1200) or ""


def is_transient_mysql_lock_error(exc: OperationalError) -> bool:
    """Indica si se cumple la operación `transient_mysql_lock_error`.

    Args:
        exc (OperationalError): Valor de `exc` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    args: tuple[object, ...] = getattr(exc.orig, "args", ())
    return bool(args) and args[0] in {1205, 1213}


def truncate_text(value: object, max_length: int) -> str | None:
    """Ejecuta la operación `truncate_text`.

    Args:
        value (object): Valor que debe procesarse.
        max_length (int): Valor de `max_length` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    if value is None:
        return None
    text = str(value)
    if len(text) <= max_length:
        return text
    return text[: max_length - 3] + "..."
