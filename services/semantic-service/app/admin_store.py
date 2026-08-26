"""Implementa las responsabilidades del módulo `admin_store`."""

from __future__ import annotations

import shutil
from typing import Any

from app.admin_model_lifecycle import SemanticModelLifecycleStore
from app.admin_operation_store import SemanticOperationStore
from app.database import Database


class SemanticAdminStore(SemanticModelLifecycleStore, SemanticOperationStore):
    """Gestiona el almacenamiento de `SemanticAdmin`."""

    def __init__(self, database: Database) -> None:
        """Inicializa una instancia de `SemanticAdminStore`.

        Args:
            database (Database): Acceso a la base de datos utilizado por la operación.
        """
        self.database = database
        """Estado de instancia asociado a `database`.
        """

    def overview(
        self,
        model_cache_dir: str,
        *,
        model_max_bytes: int,
        model_min_free_bytes: int,
    ) -> dict[str, Any]:
        """Ejecuta `overview` dentro de `SemanticAdminStore`.

        Args:
            model_cache_dir (str): Valor de `model_cache_dir` utilizado por la operación.
            model_max_bytes (int): Valor de `model_max_bytes` utilizado por la operación.
            model_min_free_bytes (int): Valor de `model_min_free_bytes` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
        """
        models = self.local_models()
        active = next((model for model in models if model["active"]), None)

        def count_active_operations(connection: Any) -> int:
            """Cuenta operaciones activas sin asumir que PostgreSQL devolvió una fila."""
            row = connection.execute(
                """
                SELECT COUNT(*) AS count
                FROM semantic_operations
                WHERE status IN ('queued', 'running', 'cancel_requested')
                """
            ).fetchone()
            return int(row["count"]) if row else 0

        operation_count = self.database.run(count_active_operations)
        model_bytes = sum(int(model["artifactBytes"]) for model in models)
        try:
            usage = shutil.disk_usage(model_cache_dir)
            disk = {
                "modelBytes": model_bytes,
                "freeBytes": usage.free,
                "totalBytes": usage.total,
                "reservedBytes": model_min_free_bytes,
                "maximumModelBytes": model_max_bytes,
            }
        except OSError:
            disk = {
                "modelBytes": model_bytes,
                "freeBytes": 0,
                "totalBytes": 0,
                "reservedBytes": model_min_free_bytes,
                "maximumModelBytes": model_max_bytes,
            }
        return {
            "service": "semantic-service",
            "searchReady": active is not None and active["index"]["complete"],
            "activeModel": active,
            "disk": disk,
            "activeOperations": operation_count,
        }
