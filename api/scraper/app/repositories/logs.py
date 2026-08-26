"""Implementa las responsabilidades del módulo `logs`.
"""
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.json_safe import json_safe
from app.db.models import ResolverLog


class ResolverLogRepository:
    """Gestiona la persistencia y consulta de `ResolverLog`.
    """
    def __init__(self, session: AsyncSession) -> None:
        """Inicializa una instancia de `ResolverLogRepository`.

        Args:
            session (AsyncSession): Sesión de base de datos utilizada por la operación.
        """
        self.session = session
        """Estado de instancia asociado a `session`.
        """

    async def add(
        self,
        phase: str,
        status: str,
        download_source_id: uuid.UUID | None = None,
        message: str | None = None,
        safe_metadata: dict | None = None,
    ) -> None:
        """Ejecuta `add` dentro de `ResolverLogRepository`.

        Args:
            phase (str): Valor de `phase` utilizado por la operación.
            status (str): Valor de `status` utilizado por la operación.
            download_source_id (uuid.UUID | None): Identificador de `download_source` utilizado por
                la operación.
            message (str | None): Mensaje que debe procesarse.
            safe_metadata (dict | None): Valor de `safe_metadata` utilizado por la operación.
        """
        self.session.add(
            ResolverLog(
                download_source_id=download_source_id,
                phase=phase,
                status=status,
                message=message,
                safe_metadata=json_safe(safe_metadata),
            )
        )
