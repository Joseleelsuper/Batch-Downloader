"""Ejecuta trabajos administrativos de modelos con reserva renovable, cancelación cooperativa y
archivos locales validados.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import shutil
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path
from typing import Any

import httpx

from app.admin_rows import directory_bytes
from app.admin_store import SemanticAdminStore
from app.config import get_settings
from app.database import Database
from app.heartbeat import WorkerHeartbeat
from app.indexer import SemanticIndexer
from app.retention import SemanticRetentionStore

logger = logging.getLogger("semantic-model-worker")



class LeaseHeartbeat:
    """Renueva en un hilo independiente la reserva de una operación mientras el trabajador
    ejecuta fases prolongadas.

    See Also:
        app.admin_operation_store.SemanticOperationStore.renew_operation: Solo renueva
            reservas running del mismo propietario.
    """

    def __init__(
        self,
        store: SemanticAdminStore,
        operation_id: str,
        owner: str,
        lease_seconds: int,
    ) -> None:
        """Prepara la señal de parada y el hilo de renovación sin iniciarlo todavía.

        Args:
            store: Composición de almacenes administrativos usada para renovar la reserva.
            operation_id: UUID de la operación administrativa persistida.
            owner: Identidad exclusiva del trabajador que reserva los trabajos.
            lease_seconds: Segundos de vigencia de la reserva a partir del reloj de
                PostgreSQL.
        """
        self.store = store

        self.operation_id = operation_id

        self.owner = owner

        self.lease_seconds = lease_seconds

        self.stopped = threading.Event()

        self.thread = threading.Thread(target=self._run, daemon=True)


    def __enter__(self) -> LeaseHeartbeat:
        """Inicia la renovación al entrar en el contexto de ejecución de una operación.

        Returns:
            este coordinador para mantener vivo el contexto.
        """
        self.thread.start()
        return self

    def __exit__(self, *_args: object) -> None:
        """Solicita detener la renovación y espera hasta dos segundos a que termine el hilo.

        Args:
            _args: Información de salida del contexto; la renovación se detiene haya o no una
                excepción.
        """
        self.stopped.set()
        self.thread.join(timeout=2)

    def _run(self) -> None:
        """Renueva cada tercio de la reserva, con mínimo de cinco segundos; registra fallos y
        vuelve a intentar mientras el contexto esté activo.
        """
        interval = max(5.0, self.lease_seconds / 3)
        while not self.stopped.wait(interval):
            try:
                self.store.operations.renew_operation(
                    self.operation_id,
                    self.owner,
                    self.lease_seconds,
                )
            except Exception:
                logger.exception("semantic_operation_lease_renewal_failed")


def _raise_subprocess_failure(stdout: str, stderr: str, returncode: int) -> None:
    """Propaga el código semantic_ de la última línea JSON o clasifica el fallo como error
    genérico de subproceso.

    Args:
        stdout: Salida del subproceso; la última línea no vacía puede contener un resultado
            JSON.
        stderr: Salida de diagnóstico del subproceso; solo se registra su cola ante un fallo
            no clasificado.
        returncode: Código de salida no nulo del subproceso.

    Raises:
        RuntimeError: Siempre: conserva un código semántico reconocido o usa
            semantic_model_subprocess_failed.
    """
    lines = [line for line in stdout.splitlines() if line.strip()]
    if lines:
        try:
            failure = json.loads(lines[-1])
            error_code = (
                str(failure.get("errorCode") or "") if isinstance(failure, dict) else ""
            )
            if error_code.startswith("semantic_"):
                raise RuntimeError(error_code)
        except json.JSONDecodeError:
            pass
    logger.error(
        "semantic_subprocess_failed code=%s stderr=%s",
        returncode,
        stderr[-2000:],
    )
    raise RuntimeError("semantic_model_subprocess_failed")


def _require_manifest_files(
    root: Path, files: list[Path], expected_files: list[dict[str, Any]],
) -> None:
    """Exige pesos safetensors y presencia y tamaño de archivos seguros declarados en el
    manifiesto.

    Args:
        root: Directorio raíz del artefacto local que se inspecciona.
        files: Archivos encontrados bajo el artefacto, excluyendo la carpeta .cache.
        expected_files: Entradas del manifiesto con ruta relativa y tamaño esperado en bytes.

    Raises:
        RuntimeError: Si faltan pesos, archivos esperados o no coinciden tamaños positivos del
            manifiesto.
    """
    if not files or not any(path.suffix == ".safetensors" for path in files):
        raise RuntimeError("semantic_model_incompatible_safetensors_required")
    actual_by_name = {path.relative_to(root).as_posix(): path for path in files}
    expected_by_name = {
        str(row.get("path") or ""): int(row.get("size") or 0)
        for row in expected_files
        if row.get("path")
        and not str(row["path"])
        .lower()
        .endswith((".bin", ".pkl", ".pickle", ".pt", ".pth", ".py"))
    }
    missing = sorted(set(expected_by_name) - set(actual_by_name))
    if missing:
        raise RuntimeError("semantic_model_incompatible_manifest_incomplete")
    for name, expected_size in expected_by_name.items():
        if expected_size > 0 and actual_by_name[name].stat().st_size != expected_size:
            raise RuntimeError("semantic_model_incompatible_manifest_size")


class SemanticModelWorker:
    """Reserva y ejecuta comparación, preparación, activación y borrado de modelos manteniendo
    los cambios de disco fuera de las transacciones largas.

    See Also:
        app.admin_store.SemanticAdminStore: Composición de consultas, cola y ciclo de vida.
        app.indexer.SemanticIndexer: Construcción del índice candidato.
    """

    def __init__(self) -> None:
        """Compone configuración, almacenes, identidad de reserva y latidos; no abre conexiones
        ni carga modelos.
        """
        self.settings = get_settings()

        self.database = Database(self.settings)

        self.store = SemanticAdminStore(self.database)

        self.owner = f"model-worker-{uuid.uuid4()}"

        self.artifacts_root = Path(self.settings.model_cache_dir) / "artifacts"

        self.manual_root = Path(self.settings.model_cache_dir) / "manual"
        """Directorio que contiene exclusivamente modelos aprovisionados a mano."""
        self.retention = SemanticRetentionStore(self.database)
        """Poda acotada de trabajo operativo; los benchmarks quedan fuera."""
        self.next_retention_at = 0.0
        """Instante monotónico de la siguiente pasada de retención."""
        self.heartbeat = WorkerHeartbeat(
            self.database,
            "model-worker",
            interval_seconds=self.settings.worker_heartbeat_interval_seconds,
        )
        """Señal persistente de salud del supervisor del model worker."""

    def open(self) -> None:
        """Abre el pool, verifica esquema, inicia latidos y reconcilia rutas locales de los
        modelos registrados.
        """
        self.database.open()
        self.database.verify_schema()
        self.heartbeat.start()
        self.artifacts_root.mkdir(parents=True, exist_ok=True)
        (self.artifacts_root / ".staging").mkdir(parents=True, exist_ok=True)
        self.manual_root.mkdir(parents=True, exist_ok=True)
        self.reconcile_registered_models()

    def close(self) -> None:
        """Detiene los latidos antes de cerrar las conexiones del proceso."""
        self.heartbeat.close()
        self.database.close()

    def run_once(self) -> bool:
        """Reserva como máximo una operación y renueva su lease hasta registrar éxito,
        cancelación o fallo seguro.
        Una preparación interrumpida restaura el estado anterior; una fallida marca el
        despliegue según siga activo o no.

        Returns:
            True si se reservó trabajo, aunque la fase falle; False si la cola no tiene
                trabajo disponible.
        """
        operation = self.store.operations.claim_operation(
            self.owner,
            self.settings.operation_lease_seconds,
        )
        if operation is None:
            return False
        operation_id = str(operation["id"])
        with LeaseHeartbeat(
            self.store,
            operation_id,
            self.owner,
            self.settings.operation_lease_seconds,
        ):
            try:
                result = self._execute(operation)
                if self.store.operations.cancel_requested(operation_id):
                    self.store.operations.mark_cancelled(operation_id)
                else:
                    self.store.operations.complete_operation(operation_id, result)
            except InterruptedError:
                if operation["operation_kind"] == "prepare" and operation["model_id"]:
                    self.store.lifecycle.restore_deployment_state(str(operation["model_id"]))
                self.store.operations.mark_cancelled(operation_id)
            except Exception as exception:
                code = _error_code(exception)
                logger.exception(
                    "semantic_operation_failed id=%s kind=%s code=%s",
                    operation_id,
                    operation["operation_kind"],
                    code,
                )
                if operation["operation_kind"] == "prepare" and operation["model_id"]:
                    self.store.lifecycle.mark_deployment_failed(str(operation["model_id"]))
                self.store.operations.fail_operation(
                    operation_id,
                    code,
                    _safe_message(code),
                )
        return True

    def run_loop(self) -> None:
        """Procesa trabajo dentro de la ventana configurada, aplica retención y mantiene latidos;
        los fallos de iteración no detienen el bucle.
        """
        while True:
            try:
                self._prune_if_due()
                if not self.settings.background_window_open():
                    self.heartbeat.success()
                    time.sleep(min(60.0, max(0.5, self.settings.operation_poll_seconds)))
                    continue
                worked = self.run_once()
                self.heartbeat.success()
            except Exception as exception:  # supervisor de proceso persistente
                self.heartbeat.failure(exception)
                logger.exception(
                    "semantic_model_worker_iteration_failed error=%s",
                    exception.__class__.__name__,
                )
                time.sleep(max(0.5, self.settings.operation_poll_seconds))
                continue
            if not worked:
                time.sleep(max(0.5, self.settings.operation_poll_seconds))

    def _prune_if_due(self) -> None:
        """Ejecuta la retención cuando vence su intervalo monotónico y registra los conteos; un
        fallo de limpieza no impide procesar la cola.
        """
        current = time.monotonic()
        if current < self.next_retention_at:
            return
        self.next_retention_at = current + self.settings.retention_interval_seconds
        try:
            result = self.retention.prune()
        except Exception as exception:
            logger.warning(
                "semantic_retention_failed error=%s",
                exception.__class__.__name__,
            )
            return
        if sum(result.values()):
            logger.info(
                "semantic_retention_pruned embedding_jobs=%s operations=%s",
                result["embeddingJobs"],
                result["operations"],
            )

    def reconcile_registered_models(self) -> None:
        """Reconcilia tamaños y rutas ausentes a partir de carpetas de artefactos o importación
        manual que ya existen en disco.
        """
        for model in self.store.models.list_models():
            artifact = self.store.models.artifact(model["id"])
            local_path = artifact.get("local_path")
            if local_path and Path(local_path).is_dir():
                self.store.models.reconcile_artifact_path(
                    model["id"],
                    local_path=local_path,
                    artifact_bytes=directory_bytes(local_path),
                )
                continue
            snapshot = self._local_artifact(
                model["id"],
                model["repository"],
                model["revision"],
            )
            if snapshot:
                self.store.models.reconcile_artifact_path(
                    model["id"],
                    local_path=str(snapshot),
                    artifact_bytes=directory_bytes(snapshot),
                )

    def _execute(self, operation: dict[str, Any]) -> dict[str, Any]:
        """Despacha el trabajo reservado a la fase correspondiente sin abrir otra reserva.

        Args:
            operation: Fila interna reservada que contiene tipo de trabajo, modelo y
                parámetros originales.

        Returns:
            resultado de la fase ejecutada.

        Raises:
            RuntimeError: Si el tipo de operación no está soportado.
        """
        kind = operation["operation_kind"]
        if kind == "benchmark":
            return self._benchmark(operation)
        if kind == "prepare":
            return self._prepare(operation)
        if kind == "activate":
            return self._activate(operation)
        if kind == "delete":
            return self._delete(operation)
        raise RuntimeError("unsupported_semantic_operation")

    def _benchmark(self, operation: dict[str, Any]) -> dict[str, Any]:
        """Ejecuta la comparación de modelos en un subproceso que publica progreso e informes de
        la operación.

        Args:
            operation: Fila interna reservada que contiene tipo de trabajo, modelo y
                parámetros originales.

        Returns:
            resultado JSON de la ejecución completa.

        Raises:
            RuntimeError: Si no se indicaron modelos o falla el benchmark.
            InterruptedError: Si se solicita cancelar el subproceso.
        """
        operation_id = str(operation["id"])
        request = dict(operation["request_payload"] or {})
        model_ids = [str(value) for value in request.get("modelIds") or []]
        if not model_ids:
            raise RuntimeError("semantic_benchmark_models_required")
        self.store.operations.update_operation(
            operation_id,
            phase="benchmarking",
            current=0,
            total=len(model_ids),
            unit="models",
            message="Preparando la comparativa reproducible",
        )
        command = [
            sys.executable,
            "-m",
            "app.benchmark_runner",
            "--operation-id",
            operation_id,
        ]
        for model_id in model_ids:
            command.extend(["--model-id", model_id])
        return self._run_json_subprocess(operation_id, command)

    def _prepare(self, operation: dict[str, Any]) -> dict[str, Any]:
        """Sincroniza e indexa el candidato y publica ready solo al completar cobertura y
        conservar la reserva final.

        Args:
            operation: Fila interna reservada que contiene tipo de trabajo, modelo y
                parámetros originales.

        Returns:
            UUID del artefacto y reporte de cobertura.

        Raises:
            RuntimeError: Si no se alcanza cobertura completa.
            InterruptedError: Si se cancela antes de publicar el índice.
        """
        operation_id = str(operation["id"])
        model_id = str(operation["model_id"])
        model_version = self.store.lifecycle.mark_preparing(model_id)
        indexer = SemanticIndexer(settings=self.settings, database=self.database)
        report = indexer.run_once(
            model_version,
            progress=lambda phase, current, total: self.store.operations.update_operation(
                operation_id,
                phase=phase,
                current=current,
                total=total,
                unit="documents",
                message="Preparando la proyección del catálogo",
            ),
            cancelled=lambda: self.store.operations.cancel_requested(operation_id),
        )
        if not report["complete"]:
            raise RuntimeError("semantic_model_coverage_incomplete")
        if not self.store.operations.begin_finalization(
            operation_id,
            owner=self.owner,
            phase="finalizing",
            message="Publicando el índice preparado",
        ):
            raise InterruptedError("semantic_operation_cancelled")
        self.store.lifecycle.mark_ready(model_version)
        return {"modelId": model_id, **report}

    def _activate(self, operation: dict[str, Any]) -> dict[str, Any]:
        """Calienta el candidato en el API y entra en la fase indivisible antes de validar
        benchmark y cambiar el activo.

        Args:
            operation: Fila interna reservada que contiene tipo de trabajo, modelo y
                parámetros originales.

        Returns:
            resultado del intercambio atómico de modelo e índice.

        Raises:
            RuntimeError: Si falla el calentamiento o alguna precondición de activación.
            InterruptedError: Si se cancela antes de comenzar el intercambio.
        """
        operation_id = str(operation["id"])
        model_id = str(operation["model_id"])
        request = dict(operation["request_payload"] or {})
        self._cancel_checkpoint(operation_id)
        self.store.operations.update_operation(
            operation_id,
            phase="warming",
            message="Cargando y calentando el candidato",
        )
        response = httpx.post(
            (
                self.settings.service_url.rstrip("/")
                + f"/internal/v1/admin/semantic/models/{model_id}/warm"
            ),
            headers={
                "X-Internal-Service-Token": (
                    self.settings.internal_service_token.get_secret_value()
                )
            },
            timeout=900,
        )
        if response.status_code >= 400:
            raise RuntimeError("semantic_model_warmup_failed")
        self._cancel_checkpoint(operation_id)
        if not self.store.operations.begin_activation(operation_id, owner=self.owner):
            raise InterruptedError("semantic_operation_cancelled")
        return self.store.lifecycle.activate_model(
            model_id,
            operation_id=operation_id,
            benchmark_run_id=str(request["benchmarkRunId"]),
            expected_current_model_id=(
                str(request["expectedCurrentModelId"])
                if request.get("expectedCurrentModelId")
                else None
            ),
            confirm_regression=bool(request.get("confirmRegression", False)),
        )

    def _delete(self, operation: dict[str, Any]) -> dict[str, Any]:
        """Reserva el borrado, retira archivos dentro de la caché y confirma la eliminación en el
        almacén.

        Args:
            operation: Fila interna reservada que contiene tipo de trabajo, modelo y
                parámetros originales.

        Returns:
            identidad del artefacto eliminado.

        Raises:
            RuntimeError: Si el modelo está en uso, la ruta sale de la caché o cambia el
                estado de borrado.
            InterruptedError: Si la cancelación precede a la fase indivisible.
        """
        operation_id = str(operation["id"])
        model_id = str(operation["model_id"])
        self._cancel_checkpoint(operation_id)
        if not self.store.operations.begin_finalization(
            operation_id,
            owner=self.owner,
            phase="deleting",
            message="Retirando el artefacto y su índice",
        ):
            raise InterruptedError("semantic_operation_cancelled")
        reservation = self.store.lifecycle.begin_model_deletion(
            model_id,
            excluding_operation_id=operation_id,
        )
        local_path = reservation.get("localPath")
        if local_path:
            managed = Path(local_path)
            self._assert_model_cache_path(managed)
            self._safe_remove(managed)
        return self.store.lifecycle.finish_model_deletion(
            model_id,
            operation_id=operation_id,
            model_version=reservation.get("modelVersion"),
        )

    def _validate_subprocess(
        self,
        operation_id: str,
        path: Path,
        *,
        query_prefix: str,
        passage_prefix: str,
    ) -> dict[str, Any]:
        """Valida carga y codificación del artefacto en un proceso con acceso remoto
        deshabilitado.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            path: Ruta del artefacto o recurso local sobre el que actúa la fase.
            query_prefix: Prefijo que el modelo antepone a consultas antes de codificarlas.
            passage_prefix: Prefijo que el modelo antepone a los documentos del catálogo.

        Returns:
            metadatos de dimensiones y compatibilidad obtenidos por el validador.
        """
        return self._run_json_subprocess(
            operation_id,
            [
                sys.executable,
                "-m",
                "app.model_validation",
                "--path",
                str(path),
                "--query-prefix",
                query_prefix,
                "--passage-prefix",
                passage_prefix,
                "--device",
                self.settings.device,
            ],
            offline=True,
        )

    def _run_json_subprocess(
        self,
        operation_id: str,
        command: list[str],
        *,
        offline: bool = False,
    ) -> dict[str, Any]:
        """Ejecuta un comando y espera su resultado JSON consultando cancelación cada segundo.
        Al cancelar solicita terminar el proceso, espera diez segundos y lo mata si aún no ha
        salido.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            command: Lista de argumentos que se ejecuta directamente, sin pasar por un shell.
            offline: Si es True, fuerza también las variables offline de Hugging Face y
                Transformers.

        Returns:
            JSON de la última línea no vacía de la salida estándar.

        Raises:
            InterruptedError: Si se solicita cancelación.
            RuntimeError: Si el proceso falla o no emite resultado.
            json.JSONDecodeError: Si la última línea de una salida correcta no es JSON válido.
        """
        environment = os.environ.copy()
        environment["HF_HUB_DISABLE_PROGRESS_BARS"] = "1"
        if offline:
            environment["HF_HUB_OFFLINE"] = "1"
            environment["TRANSFORMERS_OFFLINE"] = "1"
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=environment,
        )
        while process.poll() is None:
            if self.store.operations.cancel_requested(operation_id):
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                raise InterruptedError("semantic_operation_cancelled")
            time.sleep(1)
        stdout, stderr = process.communicate()
        if process.returncode != 0:
            if self.store.operations.cancel_requested(operation_id):
                raise InterruptedError("semantic_operation_cancelled")
            _raise_subprocess_failure(stdout, stderr, process.returncode)
        lines = [line for line in stdout.splitlines() if line.strip()]
        if not lines:
            raise RuntimeError("semantic_model_subprocess_empty")
        return json.loads(lines[-1])

    def _verify_and_digest(
        self,
        root: Path,
        *,
        expected_files: list[dict[str, Any]],
    ) -> str:
        """Verifica manifiesto, contención de rutas y formatos seguros antes de calcular una
        huella de nombres y bytes.
        Rechaza pesos serializados inseguros, archivos Python y auto_map que requiera código
        remoto.

        Args:
            root: Directorio raíz del artefacto local que se inspecciona.
            expected_files: Entradas del manifiesto con ruta relativa y tamaño esperado en
                bytes.

        Returns:
            SHA-256 del contenido local en orden estable de rutas.

        Raises:
            RuntimeError: Si faltan archivos, cambian tamaños, una ruta escapa o se requiere
                código remoto.
        """
        digest = hashlib.sha256()
        files = sorted(
            path
            for path in root.rglob("*")
            if path.is_file() and ".cache" not in path.relative_to(root).parts
        )
        _require_manifest_files(root, files, expected_files)
        for path in files:
            resolved = path.resolve()
            if not resolved.is_relative_to(root.resolve()):
                raise RuntimeError("semantic_model_incompatible_path_escape")
            if path.suffix.lower() in {".bin", ".pkl", ".pickle", ".pt", ".pth", ".py"}:
                raise RuntimeError("semantic_model_incompatible_unsafe_file")
            relative = path.relative_to(root).as_posix()
            digest.update(relative.encode())
            with path.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk)
        config_path = root / "config.json"
        if config_path.exists():
            config = json.loads(config_path.read_text(encoding="utf-8"))
            if config.get("auto_map"):
                raise RuntimeError("semantic_model_incompatible_remote_code")
        return digest.hexdigest()

    def _cancel_checkpoint(
        self,
        operation_id: str,
        *,
        cleanup: Path | None = None,
    ) -> None:
        """Interrumpe la fase cuando existe una solicitud persistente de cancelación y retira el
        temporal indicado.

        Args:
            operation_id: UUID de la operación administrativa persistida.
            cleanup: Ruta temporal opcional que debe retirarse al detectar la cancelación.

        Raises:
            InterruptedError: Si la operación tiene cancelación solicitada.
        """
        if not self.store.operations.cancel_requested(operation_id):
            return
        if cleanup and cleanup.exists():
            self._safe_remove(cleanup)
        raise InterruptedError("semantic_operation_cancelled")

    def _local_artifact(
        self,
        model_id: str,
        repository: str,
        revision: str,
    ) -> Path | None:
        """Busca primero la carpeta administrada por UUID y después la importación manual con
        revisión o sin ella.

        Args:
            model_id: UUID del artefacto registrado.
            repository: Repositorio del que procede el artefacto local.
            revision: Revisión fija que identifica el directorio de pesos.

        Returns:
            primer directorio disponible o None si no se encuentra el artefacto.
        """
        directory_name = repository.replace("/", "--")
        candidates = (
            self.artifacts_root / model_id,
            self.manual_root / directory_name / revision,
            self.manual_root / directory_name,
        )
        return next((path for path in candidates if path.is_dir()), None)

    def _assert_model_cache_path(self, path: Path) -> None:
        """Exige que la ruta resuelta sea descendiente de la caché y no la propia raíz antes de
        borrar.

        Args:
            path: Ruta del artefacto o recurso local sobre el que actúa la fase.

        Raises:
            RuntimeError: Si la ruta resuelta es la caché completa o queda fuera de ella.
        """
        cache = Path(self.settings.model_cache_dir).resolve()
        resolved = path.resolve()
        if resolved == cache or not resolved.is_relative_to(cache):
            raise RuntimeError("semantic_model_path_outside_cache")

    def _safe_remove(self, path: Path) -> None:
        """Comprueba la pertenencia a la caché y elimina el archivo o directorio indicado; una
        ruta ausente no requiere acción.

        Args:
            path: Ruta del artefacto o recurso local sobre el que actúa la fase.
        """
        self._assert_model_cache_path(path)
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()


def _error_code(exception: Exception) -> str:
    """Conserva códigos semantic_ sin detalles posteriores a dos puntos; otros fallos se
    clasifican por nombre de excepción.

    Args:
        exception: Fallo capturado en una fase del trabajador.

    Returns:
        código seguro de hasta 120 caracteres.
    """
    message = str(exception).strip()
    if message.startswith("semantic_"):
        return message.split(":", 1)[0][:120]
    return f"semantic_{exception.__class__.__name__.lower()}"[:120]


def _safe_message(code: str) -> str:
    """Traduce códigos conocidos a explicaciones administrativas y usa un mensaje genérico para
    los restantes.

    Args:
        code: Código estable de error que se convierte en un mensaje seguro para
            administración.

    Returns:
        mensaje sin incluir directamente texto arbitrario de la excepción.
    """
    messages = {
        "semantic_model_too_large": "El modelo supera el tamaño permitido.",
        "semantic_model_insufficient_disk": (
            "No hay espacio libre suficiente para descargar el modelo."
        ),
        "semantic_model_coverage_incomplete": (
            "El índice no alcanzó la cobertura completa del catálogo."
        ),
        "semantic_activation_conflict": (
            "El modelo activo cambió durante la operación. Actualiza la página."
        ),
        "semantic_benchmark_required": "Hace falta un benchmark completo y vigente.",
        "benchmark_regression_confirmation_required": (
            "El candidato rinde peor que el modelo activo y necesita confirmación."
        ),
        "semantic_model_warmup_failed": "El modelo no pudo cargarse antes de la activación.",
    }
    return messages.get(code, "La operación semántica no pudo completarse.")


def main() -> None:
    """Ejecuta una iteración con --once o el bucle de la cola, y cierra los recursos al terminar."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    arguments = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    worker = SemanticModelWorker()
    worker.open()
    try:
        if arguments.once:
            worker.run_once()
        else:
            worker.run_loop()
    finally:
        worker.close()


if __name__ == "__main__":
    main()
