"""Centraliza reserva, finalización, estado actual y normalización segura de errores del
pipeline.
"""

from __future__ import annotations

import asyncio
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy.exc import StatementError

from app.core.config import Settings
from app.core.json_safe import json_safe
from app.core.logging import get_logger
from app.db.enums import ScrapeOutcome
from app.db.models import ScraperWorkItem
from app.repositories.pipeline import PipelineRepository
from app.repositories.runs import ScrapeRunRepository
from app.scraper.pipeline_runtime import (
    async_session_local,
    retry_database_pool_operation,
)
from app.scraper.winstall import WinstallApp, parse_winstall_app

logger = get_logger(__name__)


async def claim_item(
    settings: Settings,
    queue: str,
    worker_id_value: str,
    *,
    run_id: uuid.UUID | None = None,
) -> ScraperWorkItem | None:
    """Reserva el siguiente mensaje con reintentos de pool y bloqueos MySQL y registra
    profundidad y attempts.

    Args:
        settings: Configuración del servicio y sus límites.
        queue: Nombre de la cola de trabajo.
        worker_id_value: Identidad que reserva el mensaje.
        run_id: UUID de ejecución al que se atribuye el trabajo.

    Returns:
        ScraperWorkItem o None.
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
    """Comprueba si una cola aún conserva trabajo queued o in_progress para decidir cuándo drenar
    workers.

    Args:
        settings: Configuración del servicio y sus límites.
        queue: Nombre de la cola de trabajo.
        run_id: UUID de ejecución al que se atribuye el trabajo.

    Returns:
        True si hay trabajo.
    """

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
    """Aplica complete, discard, requeue o fail en una sesión independiente y conserva
    profundidad y motivo.

    Args:
        settings: Configuración del servicio y sus límites.
        item: Mensaje de pipeline reservado.
        action: Transición solicitada: complete, discard, requeue o fail.
        message: Código o detalle seguro de la transición.
        delay_seconds: Retraso antes de hacer visible un reintento.
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
    """Persiste la aplicación y fase actuales del run con reintentos de base de datos.

    Args:
        settings: Configuración del servicio y sus límites.
        run_id: UUID de ejecución al que se atribuye el trabajo.
        package_id: Identificador de paquete de la aplicación.
        app_name: Nombre visible de la aplicación.
        phase: Fase visible del procesamiento.
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
    """Normaliza la aplicación incluida en el payload o crea un detalle mínimo con el package ID
    de reserva.

    Args:
        payload: Payload JSON asociado al trabajo.
        fallback_package_id: Identificador usado si falta el objeto app.

    Returns:
        WinstallApp.
    """
    raw = payload.get("app")
    if isinstance(raw, dict):
        return parse_winstall_app(raw)
    return parse_winstall_app({"_id": fallback_package_id, "name": fallback_package_id})


def payload_package_id(payload: dict[str, Any], item: ScraperWorkItem) -> str:
    """Elige package_id del payload y usa el identificador de la fila como fallback.

    Args:
        payload: Payload JSON asociado al trabajo.
        item: Mensaje de pipeline reservado.

    Returns:
        identificador textual.
    """
    value = payload.get("package_id") or item.package_id
    return str(value)


def provider_snapshot_absence_outcome(
    has_active_verification: bool,
) -> ScrapeOutcome:
    """Distingue ausencia confirmada de necesidad de revisión según exista verificación activa.

    Args:
        has_active_verification: Indica si existe evidencia activa que confirme una ausencia.

    Returns:
        ScrapeOutcome correspondiente.
    """
    return (
        ScrapeOutcome.CONFIRMED_MISSING if has_active_verification else ScrapeOutcome.NEEDS_REVIEW
    )


def is_stale_control_command(command: Any, run_started_at: datetime) -> bool:
    """Reconoce comandos de control creados antes del inicio del run actual.

    Args:
        command: Comando administrativo pendiente.
        run_started_at: Instante en que arrancó la ejecución actual.

    Returns:
        True si deben rechazarse.
    """
    return (
        command.command in {"pause", "resume", "stop", "force_stop"}
        and command.created_at < run_started_at
    )


def first_task_failure(error: BaseException) -> BaseException:
    """Desenvuelve ExceptionGroup y devuelve la primera causa no cancelada.

    Args:
        error: Error de tarea cuya causa raíz se desea extraer.

    Returns:
        excepción raíz.
    """

    if isinstance(error, BaseExceptionGroup):
        for nested in error.exceptions:
            failure = first_task_failure(nested)
            if not isinstance(failure, asyncio.CancelledError):
                return failure
    return error


def scrape_app_failure_metadata(exc: Exception, winstall_id: str) -> dict:
    """Construye metadatos JSON seguros de un fallo de aplicación y limita statement y params
    SQL.

    Args:
        exc: Excepción SQL o de tarea que se clasifica.
        winstall_id: Identificador Winstall del elemento fallido.

    Returns:
        mapa de diagnóstico.
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
    """Obtiene detalle acotado de la causa original de una excepción SQL o general.

    Args:
        exc: Excepción SQL o de tarea que se clasifica.

    Returns:
        texto limitado.
    """
    if isinstance(exc, StatementError) and exc.orig is not None:
        return truncate_text(f"{exc.orig.__class__.__name__}: {exc.orig}", 1200) or ""
    return truncate_text(str(exc), 1200) or ""


def truncate_text(value: object, max_length: int) -> str | None:
    """Convierte un objeto a texto y lo corta al límite indicado.

    Args:
        value: Objeto que se convierte y limita a texto.
        max_length: Límite de caracteres del texto resultante.

    Returns:
        texto, None si el valor era None.
    """
    if value is None:
        return None
    text = str(value)
    if len(text) <= max_length:
        return text
    return text[: max_length - 3] + "..."
