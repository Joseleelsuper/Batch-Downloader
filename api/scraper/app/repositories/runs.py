import socket
import uuid
from datetime import timedelta

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.config import Settings
from app.core.time import utc_now
from app.db.enums import ScrapeRunStatus
from app.db.models import ScrapeRun, ScraperCommand

RUN_LOCK_STALE_MINUTES = 90


class ScrapeRunRepository:
    def __init__(self, session: AsyncSession, settings: Settings) -> None:
        self.session = session
        self.settings = settings

    async def acquire(self) -> ScrapeRun | None:
        stale_before = utc_now() - timedelta(minutes=RUN_LOCK_STALE_MINUTES)
        active = await self.session.scalar(
            select(ScrapeRun)
            .where(ScrapeRun.status == ScrapeRunStatus.RUNNING.value)
            .where(ScrapeRun.heartbeat_at >= stale_before)
            .order_by(ScrapeRun.started_at.desc())
            .limit(1)
        )
        if active:
            return None

        run = ScrapeRun(status=ScrapeRunStatus.RUNNING.value, worker_id=worker_id())
        self.session.add(run)
        await self.session.flush()
        return run

    async def recover_running(self, error_summary: str) -> int:
        result = await self.session.scalars(
            select(ScrapeRun).where(ScrapeRun.status == ScrapeRunStatus.RUNNING.value)
        )
        runs = list(result)
        recovered_at = utc_now()
        for run in runs:
            run.status = ScrapeRunStatus.FAILED.value
            run.finished_at = recovered_at
            run.heartbeat_at = recovered_at
            run.current_phase = ScrapeRunStatus.FAILED.value
            run.stop_requested = False
            run.paused_at = None
            run.error_summary = error_summary
        await self.session.flush()
        return len(runs)

    async def heartbeat(self, run_id: uuid.UUID, **counters: int) -> None:
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
        run = await self.session.get(ScrapeRun, run_id)
        if not run:
            return
        run.heartbeat_at = utc_now()
        run.current_phase = "paused"
        run.paused_at = utc_now()

    async def mark_stop_requested(self, run_id: uuid.UUID) -> None:
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
        run = await self.session.get(ScrapeRun, run_id)
        if not run:
            return
        run.status = status.value
        run.finished_at = utc_now()
        run.heartbeat_at = utc_now()
        run.error_summary = error_summary
        run.current_phase = status.value
        run.paused_at = None
        for key, value in counters.items():
            if hasattr(run, key):
                setattr(run, key, value)

    async def next_pending_command(self) -> ScraperCommand | None:
        return await self.session.scalar(
            select(ScraperCommand)
            .where(ScraperCommand.status == "pending")
            .order_by(ScraperCommand.created_at.asc())
            .limit(1)
        )

    async def consume_command(
        self,
        command: ScraperCommand,
        status: str = "completed",
        message: str | None = None,
    ) -> None:
        command.status = status
        command.message = message
        command.consumed_at = utc_now()


def worker_id() -> str:
    return f"{socket.gethostname()}:{uuid.uuid4()}"
