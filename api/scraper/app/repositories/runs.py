"""Coordina la reserva exclusiva de ejecuciones y el consumo persistente de órdenes de pausa,
reanudación, parada y lanzamiento.
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



class ScrapeRunRepository:
    """Mantiene exclusión mediante active_lock, progreso y comandos del scheduler en la sesión
    del llamador.
    Las operaciones no confirman automáticamente; acquire sí revierte la sesión si colisiona
    la reserva única.

    See Also:
        app.worker: Consume comandos y confirma las transiciones.
        app.db.models.ScrapeRun: Conserva manifiesto, latido y resultado.
    """
    def __init__(self, session: AsyncSession, settings: Settings) -> None:
        """Conecta el seguimiento de ejecuciones a la sesión y configuración del scheduler.

        Args:
            session: Sesión asíncrona del llamador; este decide cuándo confirmar los cambios.
            settings: Configuración del scheduler conservada por el repositorio.
        """
        self.session = session

        self.settings = settings


    async def acquire(
        self,
        *,
        scope: ScrapeScope = ScrapeScope.INCREMENTAL,
        request_id: uuid.UUID | None = None,
    ) -> ScrapeRun | None:
        """Rechaza una ejecución activa con latido reciente y recupera reservas de más de 90
        minutos antes de intentar la clave única de ejecución.

        Args:
            scope: Alcance estable de la ejecución solicitada.
            request_id: UUID opcional del comando administrativo que originó la ejecución.

        Returns:
            nueva ejecución pendiente de commit o None si ya existe un propietario.

        Raises:
            Exception: Los errores de persistencia distintos de una colisión de integridad se
                propagan al llamador.
        """
        stale_before = utc_now() - timedelta(minutes=RUN_LOCK_STALE_MINUTES)
        running = list(
            await self.session.scalars(
                select(ScrapeRun)
                .where(ScrapeRun.active_lock == 1)
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
        """Guarda las identidades objetivo y su número de paquetes y renueva el latido; no actúa
        si la ejecución falta.

        Args:
            run_id: UUID de la ejecución que se consulta o actualiza.
            app_ids: UUID textuales de las aplicaciones seleccionadas.
            winstall_ids: Identificadores de proveedor que forman el manifiesto del trabajo.
        """
        run = await self.session.get(ScrapeRun, run_id)
        if run is None:
            return
        run.target_app_ids_json = app_ids
        run.target_winstall_ids_json = winstall_ids
        run.target_count = len(winstall_ids)
        run.heartbeat_at = utc_now()

    async def recover_running(self, error_summary: str) -> int:
        """Marca fallidas las ejecuciones que seguían activas, libera exclusión, retira pausa y
        parada y finaliza sus solicitudes asociadas.

        Args:
            error_summary: Motivo resumido que se conserva al terminar o recuperar la
                ejecución.

        Returns:
            número de ejecuciones recuperadas tras hacer flush.
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
        """Renueva el latido y copia los contadores cuyos nombres existen en la entidad de
        ejecución.

        Args:
            run_id: UUID de la ejecución que se consulta o actualiza.
            counters: Contadores de progreso por nombre de atributo; se ignoran nombres
                inexistentes.
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
        """Publica paquete, nombre y fase actuales y limpia la fecha de pausa cuando la fase deja
        de ser paused.

        Args:
            run_id: UUID de la ejecución que se consulta o actualiza.
            package_id: Paquete que se publica como progreso actual.
            app_name: Nombre de la aplicación actual, o None al limpiar el progreso.
            phase: Etapa del proceso a la que pertenece el registro.
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
        """Registra fase paused y fecha de pausa y renueva el latido de la ejecución.

        Args:
            run_id: UUID de la ejecución que se consulta o actualiza.
        """
        run = await self.session.get(ScrapeRun, run_id)
        if not run:
            return
        run.heartbeat_at = utc_now()
        run.current_phase = "paused"
        run.paused_at = utc_now()

    async def mark_stop_requested(self, run_id: uuid.UUID) -> None:
        """Activa la señal persistida de parada cooperativa, publica fase stopping y retira la
        pausa.

        Args:
            run_id: UUID de la ejecución que se consulta o actualiza.
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
        """Guarda resultado y contadores finales, libera active_lock y registra fin y fase
        terminal.

        Args:
            run_id: UUID de la ejecución que se consulta o actualiza.
            status: Estado que debe quedar persistido para la operación.
            error_summary: Motivo resumido que se conserva al terminar o recuperar la
                ejecución.
            counters: Contadores de progreso por nombre de atributo; se ignoran nombres
                inexistentes.
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
        """Busca por antigüedad la primera orden pendiente de pause, resume, stop o force_stop.

        Returns:
            comando de control o None.
        """
        return await self.session.scalar(
            select(ScraperCommand)
            .where(ScraperCommand.status == "pending")
            .where(ScraperCommand.command.in_(("pause", "resume", "stop", "force_stop")))
            .order_by(ScraperCommand.created_at.asc())
            .limit(1)
        )

    async def next_pending_run_request(self) -> ScraperCommand | None:
        """Solo si no existe ejecución activa, bloquea la solicitud run_once pendiente más
        antigua y omite las bloqueadas por otro consumidor.

        Returns:
            solicitud reservada en la transacción o None.
        """
        active = await self.session.scalar(
            select(ScrapeRun.id)
            .where(ScrapeRun.active_lock == 1)
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
        """Asocia el comando a la ejecución iniciada, marca running y limpia su mensaje anterior.

        Args:
            request: Comando persistido que se asocia a la ejecución iniciada.
            run_id: UUID de la ejecución que se consulta o actualiza.
        """
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
        """Guarda estado, explicación y fecha de consumo del comando indicado; no actúa si ya no
        existe.

        Args:
            request_id: UUID opcional del comando administrativo que originó la ejecución.
            status: Estado que debe quedar persistido para la operación.
            message: Explicación opcional del estado o resultado.
        """
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
        """Añade una petición run_once pendiente con actor, alcance y selección explícitos y hace
        flush para obtener su identidad.

        Args:
            scope: Alcance estable de la ejecución solicitada.
            app_ids: UUID textuales de las aplicaciones seleccionadas.
            created_by: Identidad del actor que solicita ejecutar el scraper.

        Returns:
            comando creado sin confirmar todavía.
        """
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
        """Registra estado, mensaje y fecha de consumo de una orden administrativa.

        Args:
            command: Comando administrativo que acaba de consumirse.
            status: Estado que debe quedar persistido para la operación.
            message: Explicación opcional del estado o resultado.
        """
        command.status = status
        command.message = message
        command.consumed_at = utc_now()


def worker_id() -> str:
    """Combina nombre del equipo y UUID aleatorio para distinguir propietarios de ejecuciones.

    Returns:
        identidad con formato hostname:uuid.
    """
    return f"{socket.gethostname()}:{uuid.uuid4()}"
