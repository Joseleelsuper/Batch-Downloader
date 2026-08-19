"""Implementa las responsabilidades del módulo `runs`.
"""
import socket
import uuid
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.time import utc_now
from app.db.enums import ScrapeRunStatus, ScrapeScope
from app.db.models import ScraperCommand, ScrapeRun

RUN_LOCK_STALE_MINUTES = 90
"""Constante que define `RUN_LOCK_STALE_MINUTES`.
"""


class ScrapeRunRepository:
    """Gestiona la persistencia y consulta de `ScrapeRun`.
    """
    def __init__(self, session: AsyncSession, settings: Settings) -> None:
        """Inicializa una instancia de `ScrapeRunRepository`.

        Args:
            session (AsyncSession): Sesión de base de datos utilizada por la operación.
            settings (Settings): Configuración del servicio.
        """
        self.session = session
        """Estado de instancia asociado a `session`.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """

    async def acquire(
        self,
        *,
        scope: ScrapeScope = ScrapeScope.INCREMENTAL,
        request_id: uuid.UUID | None = None,
    ) -> ScrapeRun | None:
        """Ejecuta `acquire` dentro de `ScrapeRunRepository`.

        Returns:
            ScrapeRun | None: Resultado producido por la operación.
        """
        stale_before = utc_now() - timedelta(minutes=RUN_LOCK_STALE_MINUTES)
        running = list(
            await self.session.scalars(
                select(ScrapeRun)
                .where(ScrapeRun.status == ScrapeRunStatus.RUNNING.value)
                .order_by(ScrapeRun.started_at.desc())
                .with_for_update()
            )
        )
        recovered_at = utc_now()
        for active in running:
            if active.heartbeat_at >= stale_before:
                return None
            active.status = ScrapeRunStatus.FAILED.value
            active.active_lock = None
            active.finished_at = recovered_at
            active.heartbeat_at = recovered_at
            active.current_phase = ScrapeRunStatus.FAILED.value
            active.error_summary = "The coordinator lease expired before a new run was acquired."
            if active.request_id:
                await self.finish_run_request(
                    active.request_id,
                    status=ScrapeRunStatus.FAILED.value,
                    message="The coordinator lease expired.",
                )

        run = ScrapeRun(
            active_lock=1,
            status=ScrapeRunStatus.RUNNING.value,
            scope=scope.value,
            request_id=request_id,
            worker_id=worker_id(),
        )
        self.session.add(run)
        try:
            await self.session.flush()
        except IntegrityError:
            # Otro coordinador insertó antes el bloqueo activo singleton.
            await self.session.rollback()
            return None
        return run

    async def set_manifest(
        self,
        run_id: uuid.UUID,
        *,
        app_ids: list[str],
        winstall_ids: list[str],
    ) -> None:
        """Persiste el conjunto exacto que debe poder auditarse al cerrar la ejecución."""
        run = await self.session.get(ScrapeRun, run_id)
        if run is None:
            return
        run.target_app_ids_json = app_ids
        run.target_winstall_ids_json = winstall_ids
        run.target_count = len(winstall_ids)
        run.heartbeat_at = utc_now()

    async def recover_running(self, error_summary: str) -> int:
        """Recupera la operación `running`.

        Args:
            error_summary (str): Resumen del error que se asociará a la ejecución.

        Returns:
            int: Número de elementos afectados por la operación.
        """
        result = await self.session.scalars(
            select(ScrapeRun).where(ScrapeRun.status == ScrapeRunStatus.RUNNING.value)
        )
        runs = list(result)
        recovered_at = utc_now()
        for run in runs:
            run.status = ScrapeRunStatus.FAILED.value
            run.active_lock = None
            run.finished_at = recovered_at
            run.heartbeat_at = recovered_at
            run.current_phase = ScrapeRunStatus.FAILED.value
            run.stop_requested = False
            run.paused_at = None
            run.error_summary = error_summary
            if run.request_id:
                await self.finish_run_request(
                    run.request_id,
                    status=ScrapeRunStatus.FAILED.value,
                    message=error_summary,
                )
        await self.session.flush()
        return len(runs)

    async def heartbeat(self, run_id: uuid.UUID, **counters: int) -> None:
        """Ejecuta `heartbeat` dentro de `ScrapeRunRepository`.

        Args:
            run_id (uuid.UUID): Identificador de `run` utilizado por la operación.
            **counters (int): Valor de `counters` utilizado por la operación.
        """
        run = await self.session.get(ScrapeRun, run_id)
        if not run:
            return
        run.heartbeat_at = utc_now()
        for key, value in counters.items():
            if hasattr(run, key):
                setattr(run, key, value)

    async def set_current(
        self,
        run_id: uuid.UUID,
        package_id: str | None,
        app_name: str | None,
        phase: str | None,
    ) -> None:
        """Establece la operación `current`.

        Args:
            run_id (uuid.UUID): Identificador de `run` utilizado por la operación.
            package_id (str | None): Identificador de `package` utilizado por la operación.
            app_name (str | None): Valor de `app_name` utilizado por la operación.
            phase (str | None): Valor de `phase` utilizado por la operación.
        """
        run = await self.session.get(ScrapeRun, run_id)
        if not run:
            return
        run.heartbeat_at = utc_now()
        run.current_package_id = package_id
        run.current_app_name = app_name
        run.current_phase = phase
        if phase != "paused":
            run.paused_at = None

    async def mark_paused(self, run_id: uuid.UUID) -> None:
        """Marca la operación `paused`.

        Args:
            run_id (uuid.UUID): Identificador de `run` utilizado por la operación.
        """
        run = await self.session.get(ScrapeRun, run_id)
        if not run:
            return
        run.heartbeat_at = utc_now()
        run.current_phase = "paused"
        run.paused_at = utc_now()

    async def mark_stop_requested(self, run_id: uuid.UUID) -> None:
        """Marca la operación `stop_requested`.

        Args:
            run_id (uuid.UUID): Identificador de `run` utilizado por la operación.
        """
        run = await self.session.get(ScrapeRun, run_id)
        if not run:
            return
        run.heartbeat_at = utc_now()
        run.stop_requested = True
        run.current_phase = "stopping"
        run.paused_at = None

    async def finish(
        self,
        run_id: uuid.UUID,
        status: ScrapeRunStatus,
        error_summary: str | None = None,
        **counters: int,
    ) -> None:
        """Ejecuta `finish` dentro de `ScrapeRunRepository`.

        Args:
            run_id (uuid.UUID): Identificador de `run` utilizado por la operación.
            status (ScrapeRunStatus): Valor de `status` utilizado por la operación.
            error_summary (str | None): Resumen del error que se asociará a la ejecución.
            **counters (int): Valor de `counters` utilizado por la operación.
        """
        run = await self.session.get(ScrapeRun, run_id)
        if not run:
            return
        run.status = status.value
        run.active_lock = None
        run.finished_at = utc_now()
        run.heartbeat_at = utc_now()
        run.error_summary = error_summary
        run.current_phase = status.value
        run.paused_at = None
        for key, value in counters.items():
            if hasattr(run, key):
                setattr(run, key, value)

    async def next_pending_command(self) -> ScraperCommand | None:
        """Ejecuta `next_pending_command` dentro de `ScrapeRunRepository`.

        Returns:
            ScraperCommand | None: Resultado producido por la operación.
        """
        return await self.session.scalar(
            select(ScraperCommand)
            .where(ScraperCommand.status == "pending")
            .where(ScraperCommand.command.in_(("pause", "resume", "stop", "force_stop")))
            .order_by(ScraperCommand.created_at.asc())
            .limit(1)
        )

    async def next_pending_run_request(self) -> ScraperCommand | None:
        """Bloquea la siguiente solicitud durable sin consumir controles de una ejecución."""
        active = await self.session.scalar(
            select(ScrapeRun.id)
            .where(ScrapeRun.status == ScrapeRunStatus.RUNNING.value)
            .limit(1)
        )
        if active is not None:
            return None
        return await self.session.scalar(
            select(ScraperCommand)
            .where(ScraperCommand.status == "pending")
            .where(ScraperCommand.command == "run_once")
            .order_by(ScraperCommand.created_at.asc())
            .with_for_update(skip_locked=True)
            .limit(1)
        )

    async def mark_run_request_started(
        self,
        request: ScraperCommand,
        run_id: uuid.UUID,
    ) -> None:
        """Asocia la solicitud con el run adquirido por el coordinador."""
        request.status = "running"
        request.run_id = run_id
        request.started_at = utc_now()
        request.message = None

    async def finish_run_request(
        self,
        request_id: uuid.UUID,
        *,
        status: str,
        message: str | None = None,
    ) -> None:
        """Cierra la solicitud sin perder el vínculo con su ejecución."""
        request = await self.session.get(ScraperCommand, request_id)
        if request is None:
            return
        request.status = status
        request.message = message
        request.consumed_at = utc_now()

    async def enqueue_run_request(
        self,
        *,
        scope: ScrapeScope,
        app_ids: list[str] | None,
        created_by: str,
    ) -> ScraperCommand:
        """Crea una solicitud que sobrevivirá a reinicios del scheduler."""
        request = ScraperCommand(
            command="run_once",
            scope=scope.value,
            app_ids_json=app_ids,
            status="pending",
            created_by=created_by,
        )
        self.session.add(request)
        await self.session.flush()
        return request

    async def consume_command(
        self,
        command: ScraperCommand,
        status: str = "completed",
        message: str | None = None,
    ) -> None:
        """Ejecuta `consume_command` dentro de `ScrapeRunRepository`.

        Args:
            command (ScraperCommand): Comando que debe procesarse.
            status (str): Valor de `status` utilizado por la operación.
            message (str | None): Mensaje que debe procesarse.
        """
        command.status = status
        command.message = message
        command.consumed_at = utc_now()


def worker_id() -> str:
    """Ejecuta la operación `worker_id`.

    Returns:
        str: Resultado producido por la operación.
    """
    return f"{socket.gethostname()}:{uuid.uuid4()}"
