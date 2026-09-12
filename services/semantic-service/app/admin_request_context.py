"""Normaliza los metadatos auditados y de idempotencia de las operaciones administrativas
internas.
"""

from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Header


@dataclass(frozen=True, slots=True)
class AdminRequestContext:
    """Agrupa actor y clave de deduplicación de una petición autenticada con el token interno.

    Attributes:
        actor: Identidad auditada, normalizada a un máximo de 120 caracteres.
        idempotency_key: Clave de hasta 200 caracteres o None para no deduplicar por clave.

    See Also:
        app.admin_operation_store.SemanticOperationStore.create_operation: Comprueba
            conflictos y recupera operaciones equivalentes.
    """

    actor: str
    idempotency_key: str | None


def admin_request_context(
    actor: Annotated[str | None, Header(alias="X-Admin-Actor")] = None,
    idempotency_key: Annotated[str | None, Header(alias="Idempotency-Key")] = None,
) -> AdminRequestContext:
    """Elimina espacios y aplica los límites históricos a las cabeceras administrativas.
    Un actor vacío se registra como admin; una clave vacía desactiva solo la deduplicación por
    clave.

    Args:
        actor: Identidad auditada recibida de Core; vacía se normaliza como admin y se limita
            a 120 caracteres.
        idempotency_key: Clave opcional de deduplicación, recortada a 200 caracteres; vacía se
            convierte en None.

    Returns:
        metadatos normalizados compartidos por las rutas administrativas.
    """
    return AdminRequestContext(
        actor=(actor or "admin").strip()[:120] or "admin",
        idempotency_key=(idempotency_key or "").strip()[:200] or None,
    )


AdminContext = Annotated[AdminRequestContext, Depends(admin_request_context)]
