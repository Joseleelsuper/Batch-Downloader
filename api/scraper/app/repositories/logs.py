import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.json_safe import json_safe
from app.db.models import ResolverLog


class ResolverLogRepository:
    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def add(
        self,
        phase: str,
        status: str,
        download_source_id: uuid.UUID | None = None,
        message: str | None = None,
        safe_metadata: dict | None = None,
    ) -> None:
        self.session.add(
            ResolverLog(
                download_source_id=download_source_id,
                phase=phase,
                status=status,
                message=message,
                safe_metadata=json_safe(safe_metadata),
            )
        )
