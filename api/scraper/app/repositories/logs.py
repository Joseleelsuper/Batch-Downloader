"""Añade evidencia estructurada de las fases de resolución a la transacción que ya mantiene el
llamador.
"""
import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.json_safe import json_safe
from app.db.models import ResolverLog


class ResolverLogRepository:
    """Prepara registros de resolución sin abrir sesiones ni confirmar operaciones por separado.

    See Also:
        app.db.models.ResolverLog: Persiste fase, resultado y evidencia depurada.
    """
    def __init__(self, session: AsyncSession) -> None:
        """Conserva la sesión que debe incorporar el log junto a los cambios de la resolución.

        Args:
            session: Sesión asíncrona del llamador; este decide cuándo confirmar los cambios.
        """
        self.session = session


    async def add(
        self,
        phase: str,
        status: str,
        download_source_id: uuid.UUID | None = None,
        message: str | None = None,
        safe_metadata: dict | None = None,
    ) -> None:
        """Añade a la sesión un registro de fase y resultado, convirtiendo la evidencia a valores
        JSON sin efectuar flush ni commit.

        Args:
            phase: Etapa del proceso a la que pertenece el registro.
            status: Estado que debe quedar persistido para la operación.
            download_source_id: UUID opcional de la fuente que originó el registro.
            message: Explicación opcional del estado o resultado.
            safe_metadata: Metadatos ya depurados por el llamador; se convierten a valores
                compatibles con JSON.
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
