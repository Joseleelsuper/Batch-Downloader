"""Compone consultas de modelos, ciclo de vida y operaciones administrativas sobre el mismo pool."""

from __future__ import annotations

import shutil
from typing import Any

from app.admin_model_lifecycle import SemanticModelLifecycleStore
from app.admin_model_store import SemanticModelStore
from app.admin_operation_store import SemanticOperationStore
from app.database import Database


class SemanticAdminStore:
    """Agrupa colaboradores independientes del API administrativo sin compartir estado mediante
    herencia.

    Attributes:
        models: Consulta artefactos locales, configuración y benchmarks.
        operations: Gestiona idempotencia, reservas, progreso y reintentos.
        lifecycle: Prepara, activa y elimina modelos con sus comprobaciones atómicas.

    See Also:
        app.admin_model_store.SemanticModelStore: Catálogo local de artefactos.
        app.admin_operation_store.SemanticOperationStore: Cola persistente de operaciones.
        app.admin_model_lifecycle.SemanticModelLifecycleStore: Transiciones del modelo y su
            índice.
    """

    def __init__(self, database: Database) -> None:
        """Conecta los tres almacenes con el mismo pool, manteniendo las transacciones de cada
        operación independientes.

        Args:
            database: Pool del servicio; cada llamada run delimita la transacción de su
                operación.
        """
        self.database = database
        self.models = SemanticModelStore(database)
        self.operations = SemanticOperationStore(database)
        self.lifecycle = SemanticModelLifecycleStore(database)

    def overview(
        self,
        model_cache_dir: str,
        *,
        model_max_bytes: int,
        model_min_free_bytes: int,
    ) -> dict[str, Any]:
        """Resume modelo local activo, índice listo, operaciones pendientes y presupuesto de
        disco.
        Si no se puede consultar el directorio, informa cero bytes libres y totales sin fallar
        el resumen.

        Args:
            model_cache_dir: Directorio local cuyos bytes libres y capacidad se consultan.
            model_max_bytes: Máximo configurado de bytes de artefactos que puede ocupar el
                servicio.
            model_min_free_bytes: Reserva mínima de bytes libres que deben mantenerse para
                operaciones de modelos.

        Returns:
            estado de búsqueda, modelo activo, operaciones en curso y capacidad en bytes.
        """
        models = self.models.local_models()
        active = next((model for model in models if model["active"]), None)

        def count_active_operations(connection: Any) -> int:
            """Cuenta operaciones queued, running y cancel_requested que aún ocupan capacidad
            administrativa.

            Args:
                connection: Conexión de la transacción cedida por Database.run; no se abre
                    otra conexión.

            Returns:
                número de operaciones activas; cero si no se obtiene una fila.
            """
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
            free_bytes, total_bytes = usage.free, usage.total
        except OSError:
            free_bytes = total_bytes = 0
        disk = {
            "modelBytes": model_bytes,
            "freeBytes": free_bytes,
            "totalBytes": total_bytes,
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
