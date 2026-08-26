"""Implementa las responsabilidades del módulo `model_worker`."""

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
"""Estado global asociado a `logger`.
"""


class LeaseHeartbeat:
    """Representa el componente `LeaseHeartbeat`."""

    def __init__(
        self,
        store: SemanticAdminStore,
        operation_id: str,
        owner: str,
        lease_seconds: int,
    ) -> None:
        """Inicializa una instancia de `LeaseHeartbeat`.

        Args:
            store (SemanticAdminStore): Valor de `store` utilizado por la operación.
            operation_id (str): Identificador de `operation` utilizado por la operación.
            owner (str): Valor de `owner` utilizado por la operación.
            lease_seconds (int): Valor de `lease_seconds` utilizado por la operación.
        """
        self.store = store
        """Estado de instancia asociado a `store`.
        """
        self.operation_id = operation_id
        """Estado de instancia asociado a `operation_id`.
        """
        self.owner = owner
        """Estado de instancia asociado a `owner`.
        """
        self.lease_seconds = lease_seconds
        """Estado de instancia asociado a `lease_seconds`.
        """
        self.stopped = threading.Event()
        """Estado de instancia asociado a `stopped`.
        """
        self.thread = threading.Thread(target=self._run, daemon=True)
        """Estado de instancia asociado a `thread`.
        """

    def __enter__(self) -> LeaseHeartbeat:
        """Abre el contexto y devuelve la instancia preparada.

        Returns:
            LeaseHeartbeat: Resultado producido por la operación.
        """
        self.thread.start()
        return self

    def __exit__(self, *_args: object) -> None:
        """Cierra el contexto y libera sus recursos.

        Args:
            *_args (object): Valor de `_args` utilizado por la operación.
        """
        self.stopped.set()
        self.thread.join(timeout=2)

    def _run(self) -> None:
        """Ejecuta el paso interno `_run`."""
        interval = max(5.0, self.lease_seconds / 3)
        while not self.stopped.wait(interval):
            try:
                self.store.renew_operation(
                    self.operation_id,
                    self.owner,
                    self.lease_seconds,
                )
            except Exception:
                logger.exception("semantic_operation_lease_renewal_failed")


class SemanticModelWorker:
    """Ejecuta el procesamiento en segundo plano de `SemanticModel`."""

    def __init__(self) -> None:
        """Inicializa una instancia de `SemanticModelWorker`."""
        self.settings = get_settings()
        """Estado de instancia asociado a `settings`.
        """
        self.database = Database(self.settings)
        """Estado de instancia asociado a `database`.
        """
        self.store = SemanticAdminStore(self.database)
        """Estado de instancia asociado a `store`.
        """
        self.owner = f"model-worker-{uuid.uuid4()}"
        """Estado de instancia asociado a `owner`.
        """
        self.artifacts_root = Path(self.settings.model_cache_dir) / "artifacts"
        """Estado de instancia asociado a `artifacts_root`.
        """
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
        """Ejecuta `open` dentro de `SemanticModelWorker`."""
        self.database.open()
        self.database.verify_schema()
        self.heartbeat.start()
        self.artifacts_root.mkdir(parents=True, exist_ok=True)
        (self.artifacts_root / ".staging").mkdir(parents=True, exist_ok=True)
        self.manual_root.mkdir(parents=True, exist_ok=True)
        self.reconcile_registered_models()

    def close(self) -> None:
        """Ejecuta `close` dentro de `SemanticModelWorker`."""
        self.heartbeat.close()
        self.database.close()

    def run_once(self) -> bool:
        """Ejecuta la operación `once`.

        Returns:
            bool: Indica si se cumple la condición evaluada.
        """
        operation = self.store.claim_operation(
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
                if self.store.cancel_requested(operation_id):
                    self.store.mark_cancelled(operation_id)
                else:
                    self.store.complete_operation(operation_id, result)
            except InterruptedError:
                if operation["operation_kind"] == "prepare" and operation["model_id"]:
                    self.store.restore_deployment_state(str(operation["model_id"]))
                self.store.mark_cancelled(operation_id)
            except Exception as exception:
                code = _error_code(exception)
                logger.exception(
                    "semantic_operation_failed id=%s kind=%s code=%s",
                    operation_id,
                    operation["operation_kind"],
                    code,
                )
                if operation["operation_kind"] == "prepare" and operation["model_id"]:
                    self.store.mark_deployment_failed(str(operation["model_id"]))
                self.store.fail_operation(
                    operation_id,
                    code,
                    _safe_message(code),
                )
        return True

    def run_loop(self) -> None:
        """Ejecuta la operación `loop`."""
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
        """Ejecuta retención sin reiniciar el worker ante un fallo transitorio."""
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
        """Ejecuta `reconcile_registered_models` dentro de `SemanticModelWorker`."""
        for model in self.store.models():
            artifact = self.store.artifact(model["id"])
            local_path = artifact.get("local_path")
            if local_path and Path(local_path).is_dir():
                self.store.reconcile_artifact_path(
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
                self.store.reconcile_artifact_path(
                    model["id"],
                    local_path=str(snapshot),
                    artifact_bytes=directory_bytes(snapshot),
                )

    def _execute(self, operation: dict[str, Any]) -> dict[str, Any]:
        """Ejecuta el paso interno `_execute`.

        Args:
            operation (dict[str, Any]): Valor de `operation` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
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
        """Ejecuta el paso interno `_benchmark`.

        Args:
            operation (dict[str, Any]): Valor de `operation` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
        """
        operation_id = str(operation["id"])
        request = dict(operation["request_payload"] or {})
        model_ids = [str(value) for value in request.get("modelIds") or []]
        if not model_ids:
            raise RuntimeError("semantic_benchmark_models_required")
        self.store.update_operation(
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
        """Ejecuta el paso interno `_prepare`.

        Args:
            operation (dict[str, Any]): Valor de `operation` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            InterruptedError: Si no puede completarse la operación bajo las condiciones requeridas.
            RuntimeError: Si el estado de ejecución impide completar la operación.
        """
        operation_id = str(operation["id"])
        model_id = str(operation["model_id"])
        model_version = self.store.mark_preparing(model_id)
        indexer = SemanticIndexer(settings=self.settings, database=self.database)
        report = indexer.run_once(
            model_version,
            progress=lambda phase, current, total: self.store.update_operation(
                operation_id,
                phase=phase,
                current=current,
                total=total,
                unit="documents",
                message="Preparando la proyección del catálogo",
            ),
            cancelled=lambda: self.store.cancel_requested(operation_id),
        )
        if not report["complete"]:
            raise RuntimeError("semantic_model_coverage_incomplete")
        if not self.store.begin_finalization(
            operation_id,
            owner=self.owner,
            phase="finalizing",
            message="Publicando el índice preparado",
        ):
            raise InterruptedError("semantic_operation_cancelled")
        self.store.mark_ready(model_version)
        return {"modelId": model_id, **report}

    def _activate(self, operation: dict[str, Any]) -> dict[str, Any]:
        """Ejecuta el paso interno `_activate`.

        Args:
            operation (dict[str, Any]): Valor de `operation` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            InterruptedError: Si no puede completarse la operación bajo las condiciones requeridas.
            RuntimeError: Si el estado de ejecución impide completar la operación.
        """
        operation_id = str(operation["id"])
        model_id = str(operation["model_id"])
        request = dict(operation["request_payload"] or {})
        self._cancel_checkpoint(operation_id)
        self.store.update_operation(
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
        if not self.store.begin_activation(operation_id, owner=self.owner):
            raise InterruptedError("semantic_operation_cancelled")
        return self.store.activate_model(
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
        """Ejecuta el paso interno `_delete`.

        Args:
            operation (dict[str, Any]): Valor de `operation` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            InterruptedError: Si no puede completarse la operación bajo las condiciones requeridas.
        """
        operation_id = str(operation["id"])
        model_id = str(operation["model_id"])
        self._cancel_checkpoint(operation_id)
        if not self.store.begin_finalization(
            operation_id,
            owner=self.owner,
            phase="deleting",
            message="Retirando el artefacto y su índice",
        ):
            raise InterruptedError("semantic_operation_cancelled")
        reservation = self.store.begin_model_deletion(
            model_id,
            excluding_operation_id=operation_id,
        )
        local_path = reservation.get("localPath")
        if local_path:
            managed = Path(local_path)
            self._assert_model_cache_path(managed)
            self._safe_remove(managed)
        return self.store.finish_model_deletion(
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
        """Ejecuta el paso interno `_validate_subprocess`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            path (Path): Ruta del recurso que debe procesarse.
            query_prefix (str): Valor de `query_prefix` utilizado por la operación.
            passage_prefix (str): Valor de `passage_prefix` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.
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
        """Ejecuta el paso interno `_run_json_subprocess`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            command (list[str]): Comando que debe procesarse.
            offline (bool): Valor de `offline` utilizado por la operación.

        Returns:
            dict[str, Any]: Mapa con los datos producidos por la operación.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
            InterruptedError: Si no puede completarse la operación bajo las condiciones requeridas.
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
            if self.store.cancel_requested(operation_id):
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                raise InterruptedError("semantic_operation_cancelled")
            time.sleep(1)
        stdout, stderr = process.communicate()
        if process.returncode != 0:
            if self.store.cancel_requested(operation_id):
                raise InterruptedError("semantic_operation_cancelled")
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
                process.returncode,
                stderr[-2000:],
            )
            raise RuntimeError("semantic_model_subprocess_failed")
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
        """Ejecuta el paso interno `_verify_and_digest`.

        Args:
            root (Path): Valor de `root` utilizado por la operación.
            expected_files (list[dict[str, Any]]): Valor esperado de `files`.

        Returns:
            str: Resultado producido por la operación.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
        """
        digest = hashlib.sha256()
        files = sorted(
            path
            for path in root.rglob("*")
            if path.is_file() and ".cache" not in path.relative_to(root).parts
        )
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
        """Ejecuta el paso interno `_cancel_checkpoint`.

        Args:
            operation_id (str): Identificador de `operation` utilizado por la operación.
            cleanup (Path | None): Valor de `cleanup` utilizado por la operación.

        Throws:
            InterruptedError: Si no puede completarse la operación bajo las condiciones requeridas.
        """
        if not self.store.cancel_requested(operation_id):
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
        """Localiza un artefacto aprovisionado manualmente sin consultar redes.

        Args:
            model_id (str): Identificador estable del modelo registrado.
            repository (str): Valor de `repository` utilizado por la operación.
            revision (str): Valor de `revision` utilizado por la operación.

        Returns:
            Path | None: Resultado producido por la operación.
        """
        directory_name = repository.replace("/", "--")
        candidates = (
            self.artifacts_root / model_id,
            self.manual_root / directory_name / revision,
            self.manual_root / directory_name,
        )
        return next((path for path in candidates if path.is_dir()), None)

    def _assert_model_cache_path(self, path: Path) -> None:
        """Ejecuta el paso interno `_assert_model_cache_path`.

        Args:
            path (Path): Ruta del recurso que debe procesarse.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
        """
        cache = Path(self.settings.model_cache_dir).resolve()
        resolved = path.resolve()
        if resolved == cache or not resolved.is_relative_to(cache):
            raise RuntimeError("semantic_model_path_outside_cache")

    def _safe_remove(self, path: Path) -> None:
        """Ejecuta el paso interno `_safe_remove`.

        Args:
            path (Path): Ruta del recurso que debe procesarse.
        """
        self._assert_model_cache_path(path)
        if path.is_dir():
            shutil.rmtree(path)
        elif path.exists():
            path.unlink()


def _error_code(exception: Exception) -> str:
    """Ejecuta el paso interno `_error_code`.

    Args:
        exception (Exception): Valor de `exception` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    message = str(exception).strip()
    if message.startswith("semantic_"):
        return message.split(":", 1)[0][:120]
    return f"semantic_{exception.__class__.__name__.lower()}"[:120]


def _safe_message(code: str) -> str:
    """Ejecuta el paso interno `_safe_message`.

    Args:
        code (str): Valor de `code` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
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
    """Ejecuta el punto de entrada del módulo."""
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
