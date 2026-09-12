"""Mantiene colas persistentes, reservas de consumidores, recuperación y vistas operativas del
scraper.
Las transiciones hacen flush dentro de la sesión cedida; el coordinador conserva el commit de
cada operación.

See Also:
    app.scraper.pipeline_support: Abre sesiones independientes para las transiciones de
        workers.
    app.scraper.worker_recovery: Selecciona reintentos y resultados terminales.
"""
from __future__ import annotations

import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import delete, func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.json_safe import json_safe
from app.core.time import utc_now
from app.db.enums import ResolutionStatus, ScrapeRunStatus
from app.db.models import (
    DownloadSource,
    ScraperMetricSnapshot,
    ScrapeRun,
    ScraperWorkerSnapshot,
    ScraperWorkItem,
    SoftwareApp,
)

QUEUE_SEARCHER_FILTER = "searcher_filter"

QUEUE_FILTER_SCRAPER = "filter_scraper"

QUEUE_SCRAPER_SO_FILTER = "scraper_so_filter"

QUEUE_SO_FILTER_DESCRIPTOR = "so_filter_descriptor"

QUEUE_MANUAL_INSTALLER_ENRICHMENT = "manual_installer_enrichment"

QUEUE_WEBSITE_APP_DISCOVERY = "website_app_discovery"


# Las instantáneas alimentan el monitor administrativo en vivo; son vistas previas,
# nunca un archivo de una página oficial. Acota la entrada antes de sanearla para que
# una página grande no monopolice un worker del scraper durante una pasada de regex.
MAX_SNAPSHOT_HTML_BYTES = 24_000


STATUS_QUEUED = "queued"

STATUS_IN_PROGRESS = "in_progress"

STATUS_COMPLETED = "completed"

STATUS_DISCARDED = "discarded"

STATUS_FAILED = "failed"



@dataclass(frozen=True)
class QueuePreviewItem:
    """Resume una tarea activa para mostrar progreso sin cargar su payload completo.

    Attributes:
        id, package_id, app_name: Identidades de tarea y paquete y nombre visible opcional.
        status, attempts, updated_at: Estado, intentos realizados y última modificación.
    """
    id: str

    package_id: str

    app_name: str | None

    status: str

    attempts: int

    updated_at: object



@dataclass(frozen=True)
class QueueState:
    """Combina recuentos por estado con una muestra acotada de tareas activas de una cola.

    Attributes:
        queue: Etapa persistida del pipeline.
        counts: Recuentos por estado.
        items: Vista previa de tareas en cola o en procesamiento.
    """
    queue: str

    counts: dict[str, int]

    items: list[QueuePreviewItem]



@dataclass(frozen=True)
class WorkerSnapshotView:
    """Expone la vista previa vigente más reciente de una etapa para el monitor administrativo.

    Attributes:
        stage, package_id, app_name: Etapa y aplicación observadas.
        url, html: Página y HTML de muestra, ya acotado al guardarlo.
        captured_at: Instante UTC de captura.
    """
    stage: str

    package_id: str | None

    app_name: str | None

    url: str | None

    html: str | None

    captured_at: object



@dataclass(frozen=True)
class MetricSnapshotView:
    """Expone una muestra histórica de disponibilidad y profundidad de las cuatro colas del
    pipeline.

    Attributes:
        available, review, unavailable: Recuentos de aplicaciones por estado observado de sus
            fuentes.
        queued_searcher_filter, queued_filter_scraper, queued_scraper_so_filter,
            queued_so_filter_descriptor: Trabajo en cola o en procesamiento por transición
            entre etapas.
        captured_at: Instante de la muestra.
    """
    available: int

    review: int

    unavailable: int

    queued_searcher_filter: int

    queued_filter_scraper: int

    queued_scraper_so_filter: int

    queued_so_filter_descriptor: int

    captured_at: object



@dataclass(frozen=True)
class QueueMaintenanceResult:
    """Resume una acción administrativa de mantenimiento y el número de tareas afectadas.

    Attributes:
        action: Nombre estable de la operación ejecutada.
        affected: Cantidad de tareas modificadas.
    """
    action: str

    affected: int



class PipelineRepository:
    """Selecciona y modifica tareas durables y datos del monitor en la sesión de una operación.
    Reserva trabajo con SKIP LOCKED y conserva la identidad única por cola y paquete al
    reencolar.

    See Also:
        app.db.models.ScraperWorkItem: Persiste reserva, prioridad e intentos.
        app.scraper.pipeline_runtime: Coordina las etapas que consumen estas colas.
    """
    def __init__(self, session: AsyncSession) -> None:
        """Conserva la sesión que controla la transacción de cada transición del pipeline.

        Args:
            session: Sesión asíncrona del llamador; ninguna operación de este repositorio
                confirma la transacción.
        """
        self.session = session


    async def reset_expired_leases(self) -> int:
        """Delega la recuperación de reservas vencidas o ausentes en la misma política de trabajo
        atascado.

        Returns:
            número de tareas reencoladas.
        """
        return await self.recover_stuck()

    async def recover_stuck(self) -> int:
        """Selecciona tareas en procesamiento con reserva vencida o sin fecha, libera su
        propietario y limpia el error anterior.

        Returns:
            número de tareas disponibles de nuevo.
        """
        now = utc_now()
        result = await self.session.scalars(
            select(ScraperWorkItem)
            .where(ScraperWorkItem.status == STATUS_IN_PROGRESS)
            .where(
                or_(
                    ScraperWorkItem.lease_expires_at.is_(None),
                    ScraperWorkItem.lease_expires_at < now,
                )
            )
        )
        return await self._requeue_items(list(result), now)

    async def recover_orphaned_run_items(self) -> int:
        """Libera tareas en procesamiento sin ejecución o asociadas a una ejecución terminada,
        aunque su reserva aún no haya caducado.

        Returns:
            número de tareas reencoladas con motivo scheduler_restart_recovery.
        """
        now = utc_now()
        inactive_run_ids = select(ScrapeRun.id).where(
            ScrapeRun.status != ScrapeRunStatus.RUNNING.value
        )
        result = await self.session.scalars(
            select(ScraperWorkItem)
            .where(ScraperWorkItem.status == STATUS_IN_PROGRESS)
            .where(
                or_(
                    ScraperWorkItem.run_id.is_(None),
                    ScraperWorkItem.run_id.in_(inactive_run_ids),
                )
            )
        )
        return await self._requeue_items(list(result), now, error="scheduler_restart_recovery")

    async def retry_failed(self) -> int:
        """Selecciona solo tareas fallidas y las deja disponibles de nuevo sin reiniciar su
        contador de intentos.

        Returns:
            número de tareas reencoladas con error anterior limpio.
        """
        now = utc_now()
        result = await self.session.scalars(
            select(ScraperWorkItem).where(ScraperWorkItem.status == STATUS_FAILED)
        )
        return await self._requeue_items(list(result), now)

    async def _requeue_items(
        self, items: list[ScraperWorkItem], now: datetime, error: str | None = None
    ) -> int:
        """Libera las reservas de tareas seleccionadas y las deja disponibles para reintento.

        Args:
            items: Tareas ya seleccionadas según la política de recuperación del llamador.
            now: Instante UTC sin tzinfo para disponibilidad y última modificación.
            error: Motivo que debe quedar en la tarea; None limpia el error anterior.

        Returns:
            Número de tareas reencoladas, después de hacer flush sin confirmar la transacción.
        """
        for item in items:
            item.status = STATUS_QUEUED
            item.lease_owner = None
            item.lease_expires_at = None
            item.available_at = now
            item.last_error = error
            item.updated_at = now
        await self.session.flush()
        return len(items)

    async def enqueue(
        self,
        queue: str,
        package_id: str,
        app_name: str | None,
        payload: dict[str, Any],
        run_id: uuid.UUID | None,
        *,
        priority: int = 0,
        force: bool = False,
    ) -> ScraperWorkItem:
        """Crea o reutiliza la tarea única por cola y paquete, conserva tareas en progreso y
        reabre estados admitidos por la política de la etapa.
        Un cambio de ejecución reinicia los intentos; un cambio de huella o force permite
        regenerar contenido completado en las etapas correspondientes.

        Args:
            queue: Nombre persistido de la cola o etapa del pipeline.
            package_id: Identidad de paquete u operación, única dentro de cada cola.
            app_name: Nombre visible de aplicación, opcional durante las primeras etapas.
            payload: Datos de entrada serializables, incluida input_hash cuando la etapa
                detecta cambios.
            run_id: Ejecución asociada; None no restringe la consulta o permite trabajo
                independiente.
            priority: Prioridad numérica; los valores mayores se reservan primero.
            force: True permite reprocesar tareas completadas de filtro de plataformas o
                descripción.

        Returns:
            tarea creada o reutilizada, que puede seguir activa o completada si no corresponde
                reencolarla.
        """
        existing = await self.session.scalar(
            select(ScraperWorkItem)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.package_id == package_id)
            .limit(1)
        )
        now = utc_now()
        if existing:
            payload_changed = (existing.payload_json or {}).get("input_hash") != payload.get(
                "input_hash"
            )
            belongs_to_new_run = run_id is not None and existing.run_id != run_id
            should_requeue = existing.status in {
                STATUS_QUEUED,
                STATUS_FAILED,
                STATUS_DISCARDED,
            } or (
                existing.status == STATUS_COMPLETED
                and queue in {QUEUE_SCRAPER_SO_FILTER, QUEUE_SO_FILTER_DESCRIPTOR}
                and (force or payload_changed)
            ) or (
                existing.status == STATUS_COMPLETED
                and queue
                in {
                    QUEUE_SEARCHER_FILTER,
                    QUEUE_FILTER_SCRAPER,
                    QUEUE_SCRAPER_SO_FILTER,
                }
                and belongs_to_new_run
            )
            if should_requeue:
                existing.status = STATUS_QUEUED
                existing.payload_json = json_safe(payload)
                existing.app_name = app_name or existing.app_name
                existing.run_id = run_id or existing.run_id
                existing.priority = max(existing.priority, priority)
                existing.last_error = None
                existing.lease_owner = None
                existing.lease_expires_at = None
                if belongs_to_new_run:
                    existing.attempts = 0
                existing.available_at = now
                existing.updated_at = now
            return existing

        item = ScraperWorkItem(
            queue=queue,
            status=STATUS_QUEUED,
            package_id=package_id,
            app_name=app_name,
            payload_json=json_safe(payload),
            run_id=run_id,
            priority=priority,
            available_at=now,
        )
        self.session.add(item)
        await self.session.flush()
        return item

    async def has_active_item(self, queue: str, package_id: str) -> bool:
        """Comprueba si el paquete tiene trabajo en cola o en procesamiento en la etapa indicada.

        Args:
            queue: Nombre persistido de la cola o etapa del pipeline.
            package_id: Identidad de paquete u operación, única dentro de cada cola.

        Returns:
            True si existe trabajo activo.
        """
        item_id = await self.session.scalar(
            select(ScraperWorkItem.id)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.package_id == package_id)
            .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
            .limit(1)
        )
        return item_id is not None

    async def item_statuses(self, queue: str, package_ids: list[str]) -> dict[str, str]:
        """Consulta los estados existentes de un conjunto de paquetes en una sola cola.

        Args:
            queue: Nombre persistido de la cola o etapa del pipeline.
            package_ids: Identidades que se consultan por lotes; una lista vacía produce
                resultado vacío.

        Returns:
            mapa por paquete; los inexistentes no aparecen.
        """
        if not package_ids:
            return {}
        rows = await self.session.execute(
            select(ScraperWorkItem.package_id, ScraperWorkItem.status)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.package_id.in_(package_ids))
        )
        return {package_id: status for package_id, status in rows}

    async def active_package_ids(
        self,
        queues: tuple[str, ...],
        package_ids: list[str],
    ) -> set[str]:
        """Busca paquetes con trabajo en cola o en procesamiento en cualquiera de las etapas
        indicadas.

        Args:
            queues: Colas en las que se buscan paquetes activos.
            package_ids: Identidades que se consultan por lotes; una lista vacía produce
                resultado vacío.

        Returns:
            conjunto de identidades activas sin duplicados.
        """
        if not queues or not package_ids:
            return set()
        rows = await self.session.scalars(
            select(ScraperWorkItem.package_id)
            .where(ScraperWorkItem.queue.in_(queues))
            .where(ScraperWorkItem.package_id.in_(package_ids))
            .where(ScraperWorkItem.status.in_((STATUS_QUEUED, STATUS_IN_PROGRESS)))
        )
        return set(rows)

    async def claim_next(
        self,
        queue: str,
        worker_id: str,
        lease_seconds: int,
        *,
        run_id: uuid.UUID | None = None,
    ) -> ScraperWorkItem | None:
        """Bloquea la primera tarea disponible por prioridad descendente y antigüedad, omite
        filas bloqueadas y asigna reserva e incremento de intentos.

        Args:
            queue: Nombre persistido de la cola o etapa del pipeline.
            worker_id: Identidad del consumidor que reserva o captura trabajo.
            lease_seconds: Duración de la reserva en segundos.
            run_id: Ejecución asociada; None no restringe la consulta o permite trabajo
                independiente.

        Returns:
            tarea reservada tras flush o None si no hay una elegible.
        """
        now = utc_now()
        statement = (
            select(ScraperWorkItem.id)
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.status == STATUS_QUEUED)
            .where(ScraperWorkItem.available_at <= now)
            .order_by(
                ScraperWorkItem.priority.desc(),
                ScraperWorkItem.available_at.asc(),
                ScraperWorkItem.created_at.asc(),
            )
            .limit(1)
            .with_for_update(skip_locked=True)
        )
        if run_id is not None:
            statement = statement.where(ScraperWorkItem.run_id == run_id)
        item_id = await self.session.scalar(statement)
        if not item_id:
            return None
        item = await self.session.get(ScraperWorkItem, item_id)
        if not item:
            return None
        item.status = STATUS_IN_PROGRESS
        item.attempts += 1
        item.lease_owner = worker_id
        item.lease_expires_at = now + timedelta(seconds=lease_seconds)
        item.updated_at = now
        await self.session.flush()
        return item

    async def complete(self, item: ScraperWorkItem) -> None:
        """Marca la tarea completada, limpia el error y libera su reserva.

        Args:
            item: Tarea de la sesión actual cuyo estado se modifica.
        """
        await self._finish(item, STATUS_COMPLETED, None)

    async def discard(self, item: ScraperWorkItem, reason: str) -> None:
        """Marca la tarea descartada con un motivo y libera su reserva.

        Args:
            item: Tarea de la sesión actual cuyo estado se modifica.
            reason: Motivo del descarte o reintento, truncado a 1000 caracteres.
        """
        await self._finish(item, STATUS_DISCARDED, reason)

    async def fail(self, item: ScraperWorkItem, error: str) -> None:
        """Marca la tarea fallida con un error y libera su reserva para recuperación posterior.

        Args:
            item: Tarea de la sesión actual cuyo estado se modifica.
            error: Código o resumen del fallo, truncado a 1000 caracteres.
        """
        await self._finish(item, STATUS_FAILED, error)

    async def requeue(
        self,
        item: ScraperWorkItem,
        reason: str,
        *,
        delay_seconds: int = 2,
    ) -> None:
        """Libera la reserva y aplaza la disponibilidad de la tarea, conservando intentos y un
        motivo de reintento.

        Args:
            item: Tarea de la sesión actual cuyo estado se modifica.
            reason: Motivo del descarte o reintento, truncado a 1000 caracteres.
            delay_seconds: Pausa antes del siguiente intento, en segundos; los valores
                negativos se tratan como cero.
        """
        now = utc_now()
        item.status = STATUS_QUEUED
        item.last_error = truncate(reason, 1000)
        item.lease_owner = None
        item.lease_expires_at = None
        item.available_at = now + timedelta(seconds=max(0, delay_seconds))
        item.updated_at = now
        await self.session.flush()

    async def _finish(self, item: ScraperWorkItem, status: str, message: str | None) -> None:
        """Aplica un resultado terminal, trunca el mensaje, limpia propietario y vencimiento y
        hace flush.

        Args:
            item: Tarea de la sesión actual cuyo estado se modifica.
            status: Estado terminal o de cola que se asigna a la tarea.
            message: Motivo opcional del resultado; None limpia el error anterior.
        """
        item.status = status
        item.last_error = truncate(message, 1000)
        item.lease_owner = None
        item.lease_expires_at = None
        item.updated_at = utc_now()
        await self.session.flush()

    async def has_pending_work(self) -> bool:
        """Comprueba si existe cualquier tarea en cola o en procesamiento, incluidas las etapas
        de inspección y descubrimiento.

        Returns:
            True mientras quede trabajo activo.
        """
        count = await self.session.scalar(
            select(func.count(ScraperWorkItem.id)).where(
                ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS])
            )
        )
        return bool(count)

    async def queue_depth(
        self,
        queue: str,
        *,
        run_id: uuid.UUID | None = None,
    ) -> int:
        """Cuenta trabajo en cola o en procesamiento de una etapa, opcionalmente restringido a
        una ejecución.

        Args:
            queue: Nombre persistido de la cola o etapa del pipeline.
            run_id: Ejecución asociada; None no restringe la consulta o permite trabajo
                independiente.

        Returns:
            número de tareas activas.
        """
        return await self._count_queue(queue, run_id=run_id)

    async def queue_states(self, limit: int = 20) -> list[QueueState]:
        """Devuelve las cuatro colas del pipeline con recuentos de todos los estados y muestra
        activa que prioriza trabajo en procesamiento.

        Args:
            limit: Máximo de tareas activas mostrado por cola.

        Returns:
            estados ordenados desde descubrimiento hasta descripción.
        """
        states: list[QueueState] = []
        for queue in (
            QUEUE_SEARCHER_FILTER,
            QUEUE_FILTER_SCRAPER,
            QUEUE_SCRAPER_SO_FILTER,
            QUEUE_SO_FILTER_DESCRIPTOR,
        ):
            rows = await self.session.execute(
                select(ScraperWorkItem.status, func.count(ScraperWorkItem.id))
                .where(ScraperWorkItem.queue == queue)
                .group_by(ScraperWorkItem.status)
            )
            counts = {status: int(count) for status, count in rows}
            result = await self.session.execute(
                select(
                    ScraperWorkItem.id,
                    ScraperWorkItem.package_id,
                    ScraperWorkItem.app_name,
                    ScraperWorkItem.status,
                    ScraperWorkItem.attempts,
                    ScraperWorkItem.updated_at,
                )
                .where(ScraperWorkItem.queue == queue)
                .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
                .order_by(
                    (ScraperWorkItem.status == STATUS_IN_PROGRESS).desc(),
                    ScraperWorkItem.updated_at.desc(),
                )
                .limit(limit)
            )
            states.append(
                QueueState(
                    queue=queue,
                    counts=counts,
                    items=[
                        QueuePreviewItem(
                            id=str(item["id"]),
                            package_id=item["package_id"],
                            app_name=item["app_name"],
                            status=item["status"],
                            attempts=item["attempts"],
                            updated_at=item["updated_at"],
                        )
                        for item in result.mappings()
                    ],
                )
            )
        return states

    async def save_snapshot(
        self,
        *,
        run_id: uuid.UUID | None,
        worker_id: str,
        stage: str,
        package_id: str | None,
        app_name: str | None,
        url: str | None,
        html: str | None,
        ttl_seconds: int = 900,
    ) -> None:
        """Acota y depura el HTML y guarda una vista previa con caducidad sin tocar la ejecución
        activa ni podar otras instantáneas.

        Args:
            run_id: Identidad recibida por compatibilidad; la instantánea se guarda con
                run_id=None para no bloquear la ejecución.
            worker_id: Identidad del consumidor que reserva o captura trabajo.
            stage: Etapa que identifica la vista previa del worker.
            package_id: Identidad de paquete u operación, única dentro de cada cola.
            app_name: Nombre visible de aplicación, opcional durante las primeras etapas.
            url: Página observada durante el trabajo, o None si no existe.
            html: HTML de vista previa opcional; se limita antes de retirar scripts y
                manejadores inline.
            ttl_seconds: Vigencia de la instantánea en segundos desde la captura.
        """
        now = utc_now()
        self.session.add(
            ScraperWorkerSnapshot(
            # Las instantáneas son una vista en vivo de mejor esfuerzo. Referenciar la
            # ejecución que se actualiza hace que cada inserción bloquee su FK y puede
            # interbloquear workers concurrentes con `set_current`.
                run_id=None,
                worker_id=worker_id,
                stage=stage,
                package_id=package_id,
                app_name=app_name,
                url=url,
                html=sanitize_snapshot_html(html),
                captured_at=now,
                expires_at=now + timedelta(seconds=ttl_seconds),
            )
        )
        await self.session.flush()

    async def prune_expired_snapshots(self) -> int:
        """Elimina vistas previas cuya caducidad es anterior al instante actual y hace flush.

        Returns:
            recuento comunicado por el driver, o cero si no está disponible.
        """
        result = await self.session.execute(
            delete(ScraperWorkerSnapshot).where(
                ScraperWorkerSnapshot.expires_at < utc_now()
            )
        )
        await self.session.flush()
        return statement_rowcount(result)

    async def latest_snapshots(self) -> list[WorkerSnapshotView]:
        """Busca la captura no caducada más reciente de cada etapa del monitor, desde searcher
        hasta descriptor.

        Returns:
            vistas existentes en el orden de las etapas.
        """
        snapshots: list[WorkerSnapshotView] = []
        for stage in ("searcher", "filter", "scraper", "so_filter", "descriptor"):
            snapshot = await self.session.scalar(
                select(ScraperWorkerSnapshot)
                .where(ScraperWorkerSnapshot.stage == stage)
                .where(ScraperWorkerSnapshot.expires_at >= utc_now())
                .order_by(ScraperWorkerSnapshot.captured_at.desc())
                .limit(1)
            )
            if snapshot:
                snapshots.append(
                    WorkerSnapshotView(
                        stage=snapshot.stage,
                        package_id=snapshot.package_id,
                        app_name=snapshot.app_name,
                        url=snapshot.url,
                        html=snapshot.html,
                        captured_at=snapshot.captured_at,
                    )
                )
        return snapshots

    async def save_metric_snapshot(self, run_id: uuid.UUID | None = None) -> None:
        """Recuenta disponibilidad por estados de fuentes y profundidad de colas y guarda una
        muestra histórica asociada a la ejecución opcional.

        Args:
            run_id: Ejecución asociada; None no restringe la consulta o permite trabajo
                independiente.
        """
        available = await self._count_apps_with_statuses(
            [ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value],
        )
        review = await self._count_apps_with_statuses(
            [ResolutionStatus.REQUIRES_MANUAL_REVIEW.value],
            exclude_available=True,
        )
        unavailable = await self._count_apps_with_statuses(
            [ResolutionStatus.MISSING.value, ResolutionStatus.BROKEN.value],
            exclude_available=True,
            exclude_review=True,
        )
        queued_searcher_filter = await self._count_queue(QUEUE_SEARCHER_FILTER)
        queued_filter_scraper = await self._count_queue(QUEUE_FILTER_SCRAPER)
        queued_scraper_so_filter = await self._count_queue(QUEUE_SCRAPER_SO_FILTER)
        queued_so_filter_descriptor = await self._count_queue(QUEUE_SO_FILTER_DESCRIPTOR)
        self.session.add(
            ScraperMetricSnapshot(
                run_id=run_id,
                available=available,
                review=review,
                unavailable=unavailable,
                queued_searcher_filter=queued_searcher_filter,
                queued_filter_scraper=queued_filter_scraper,
                queued_scraper_so_filter=queued_scraper_so_filter,
                queued_so_filter_descriptor=queued_so_filter_descriptor,
                captured_at=utc_now(),
            )
        )
        await self.session.flush()

    async def metric_snapshots(self, limit: int = 60) -> list[MetricSnapshotView]:
        """Lee las muestras más recientes y las devuelve en orden temporal ascendente para
        dibujar la serie.

        Args:
            limit: Máximo de elementos de la vista solicitada.

        Returns:
            hasta limit muestras, de más antigua a más reciente.
        """
        result = await self.session.scalars(
            select(ScraperMetricSnapshot)
            .order_by(ScraperMetricSnapshot.captured_at.desc())
            .limit(limit)
        )
        return [
            MetricSnapshotView(
                available=item.available,
                review=item.review,
                unavailable=item.unavailable,
                queued_searcher_filter=item.queued_searcher_filter,
                queued_filter_scraper=item.queued_filter_scraper,
                queued_scraper_so_filter=item.queued_scraper_so_filter,
                queued_so_filter_descriptor=item.queued_so_filter_descriptor,
                captured_at=item.captured_at,
            )
            for item in reversed(list(result))
        ]

    async def _count_queue(
        self,
        queue: str,
        *,
        run_id: uuid.UUID | None = None,
    ) -> int:
        """Aplica los filtros comunes de cola, estado activo y ejecución opcional antes de contar
        tareas.

        Args:
            queue: Nombre persistido de la cola o etapa del pipeline.
            run_id: Ejecución asociada; None no restringe la consulta o permite trabajo
                independiente.

        Returns:
            cantidad de tareas elegibles, o cero si no hay ninguna.
        """
        statement = (
            select(func.count(ScraperWorkItem.id))
            .where(ScraperWorkItem.queue == queue)
            .where(ScraperWorkItem.status.in_([STATUS_QUEUED, STATUS_IN_PROGRESS]))
        )
        if run_id is not None:
            statement = statement.where(ScraperWorkItem.run_id == run_id)
        return int(await self.session.scalar(statement) or 0)

    async def _count_apps_with_statuses(
        self,
        statuses: list[str],
        *,
        exclude_available: bool = False,
        exclude_review: bool = False,
    ) -> int:
        """Cuenta aplicaciones activas con una fuente de los estados solicitados y aplica
        exclusiones para evitar solapar categorías.
        Para direct/fallback exige además validación válida de la fuente.

        Args:
            statuses: Estados de resolución de fuentes que se incluyen en el recuento.
            exclude_available: True excluye aplicaciones que tienen otra fuente directa o
                fallback válida.
            exclude_review: True excluye aplicaciones que tienen una fuente pendiente de
                revisión.

        Returns:
            cantidad de aplicaciones que cumple los criterios.
        """
        source_query = (
            select(DownloadSource.id)
            .where(DownloadSource.software_app_id == SoftwareApp.id)
            .where(DownloadSource.resolution_status.in_(statuses))
        )
        if set(statuses) == {ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value}:
            source_query = source_query.where(DownloadSource.validation_status == "valid")
        source_exists = source_query.limit(1).exists()
        stmt = (
            select(func.count(SoftwareApp.id))
            .where(SoftwareApp.app_status == "active")
            .where(source_exists)
        )
        if exclude_available:
            available_exists = (
                select(DownloadSource.id)
                .where(DownloadSource.software_app_id == SoftwareApp.id)
                .where(
                    DownloadSource.resolution_status.in_(
                        [ResolutionStatus.DIRECT.value, ResolutionStatus.FALLBACK.value]
                    )
                )
                .where(DownloadSource.validation_status == "valid")
                .limit(1)
                .exists()
            )
            stmt = stmt.where(~available_exists)
        if exclude_review:
            review_exists = (
                select(DownloadSource.id)
                .where(DownloadSource.software_app_id == SoftwareApp.id)
                .where(
                    DownloadSource.resolution_status
                    == ResolutionStatus.REQUIRES_MANUAL_REVIEW.value
                )
                .limit(1)
                .exists()
            )
            stmt = stmt.where(~review_exists)
        return int(await self.session.scalar(stmt) or 0)


def sanitize_snapshot_html(html: str | None) -> str | None:
    """Acota la entrada y retira bloques script y atributos de evento inline antes de volver a
    limitar el resultado.
    Es una depuración de vista previa, no un sanitizador HTML general para insertar contenido
    arbitrario sin aislamiento.

    Args:
        html: HTML de vista previa opcional; se limita antes de retirar scripts y manejadores
            inline.

    Returns:
        HTML de muestra o None si no se recibió contenido.
    """
    if not html:
        return None

    bounded = truncate_snapshot_bytes(html)
        # La expresión anterior de etiquetas script anidadas podía retroceder en exceso
        # con scripts grandes. Este patrón acotado y no anidado basta para la vista previa
        # del monitor y mantiene un coste predecible.
    cleaned = re.sub(
        r"<script\b[^>]*>.*?(?:</script\s*>|\Z)",
        "",
        bounded,
        flags=re.I | re.S,
    )
    cleaned = re.sub(r"\s+on[a-z]+\s*=\s*(['\"]).*?\1", "", cleaned, flags=re.I | re.S)
    cleaned = re.sub(r"\s+on[a-z]+\s*=\s*[^\s>]+", "", cleaned, flags=re.I)
    return truncate_snapshot_bytes(cleaned)


def truncate_snapshot_bytes(value: str) -> str:
    """Recorta el contenido a 24000 bytes UTF-8 sin cortar caracteres y añade una marca de
    truncamiento cuando hace falta.

    Args:
        value: HTML original que debe acotarse para la vista previa.

    Returns:
        texto original o prefijo acotado más el comentario de truncamiento, cuyos bytes son
            adicionales al límite.
    """
    encoded = value.encode("utf-8", errors="ignore")
    if len(encoded) <= MAX_SNAPSHOT_HTML_BYTES:
        return value
    preview = encoded[:MAX_SNAPSHOT_HTML_BYTES].decode("utf-8", errors="ignore")
    return preview + "\n<!-- snapshot truncated -->"


def truncate(value: str | None, max_length: int) -> str | None:
    """Conserva textos cortos y sustituye el final de los largos por tres puntos dentro del
    límite solicitado.

    Args:
        value: Texto opcional cuyo tamaño se limita.
        max_length: Máximo de caracteres del resultado; las llamadas proporcionan al menos
            tres para incluir puntos suspensivos.

    Returns:
        texto original, texto abreviado o None.
    """
    if value is None:
        return None
    return value if len(value) <= max_length else value[: max_length - 3] + "..."


def statement_rowcount(result: object) -> int:
    """Obtiene el recuento de filas del resultado SQL sin exigir una clase concreta del driver.

    Args:
        result: Resultado SQLAlchemy del que se consulta el número de filas afectadas.

    Returns:
        rowcount convertido a entero; cero si el atributo falta o es nulo.
    """
    return int(getattr(result, "rowcount", 0) or 0)
