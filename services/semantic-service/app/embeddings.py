"""Implementa las responsabilidades del módulo `embeddings`.
"""
from __future__ import annotations

import threading
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any

import numpy as np

if TYPE_CHECKING:
    from sentence_transformers import SentenceTransformer


@dataclass(frozen=True)
class RegisteredModel:
    """Representa el componente `RegisteredModel`.
    """
    model_version: str
    """Atributo de clase `model_version` de `RegisteredModel`.
    """
    artifact_id: str | None
    """Atributo de clase `artifact_id` de `RegisteredModel`.
    """
    model_key: str
    """Atributo de clase `model_key` de `RegisteredModel`.
    """
    hf_repository: str
    """Atributo de clase `hf_repository` de `RegisteredModel`.
    """
    hf_revision: str
    """Atributo de clase `hf_revision` de `RegisteredModel`.
    """
    dimensions: int
    """Atributo de clase `dimensions` de `RegisteredModel`.
    """
    query_prefix: str
    """Atributo de clase `query_prefix` de `RegisteredModel`.
    """
    passage_prefix: str
    """Atributo de clase `passage_prefix` de `RegisteredModel`.
    """
    artifact_path: str | None = None
    """Atributo de clase `artifact_path` de `RegisteredModel`.
    """
    rrf_weight: float = 1.0
    """Atributo de clase `rrf_weight` de `RegisteredModel`.
    """
    minimum_similarity: float = 0.0
    """Atributo de clase `minimum_similarity` de `RegisteredModel`.
    """

    @classmethod
    def from_row(cls, row: dict[str, Any]) -> RegisteredModel:
        """Ejecuta `from_row` dentro de `RegisteredModel`.

        Args:
            row (dict[str, Any]): Valor de `row` utilizado por la operación.

        Returns:
            RegisteredModel: Resultado producido por la operación.
        """
        return cls(
            model_version=row["model_version"],
            artifact_id=(
                str(row["artifact_id"])
                if row.get("artifact_id") is not None
                else None
            ),
            model_key=row["model_key"],
            hf_repository=row["hf_repository"],
            hf_revision=row["hf_revision"],
            dimensions=int(row["dimensions"]),
            query_prefix=row["query_prefix"],
            passage_prefix=row["passage_prefix"],
            artifact_path=row.get("artifact_path"),
            rrf_weight=float(row.get("rrf_weight") or 1.0),
            minimum_similarity=float(row.get("minimum_similarity") or 0.0),
        )


class EmbeddingRuntime:
    """Mantiene el estado de ejecución de `Embedding`.
    """

    def __init__(
        self,
        registered: RegisteredModel,
        *,
        device: str,
        cache_dir: str,
        batch_size: int = 32,
    ) -> None:
        """Inicializa una instancia de `EmbeddingRuntime`.

        Args:
            registered (RegisteredModel): Valor de `registered` utilizado por la operación.
            device (str): Valor de `device` utilizado por la operación.
            cache_dir (str): Valor de `cache_dir` utilizado por la operación.
            batch_size (int): Valor de `batch_size` utilizado por la operación.
        """
        self.registered = registered
        """Estado de instancia asociado a `registered`.
        """
        self.device = device
        """Estado de instancia asociado a `device`.
        """
        self.cache_dir = cache_dir
        """Estado de instancia asociado a `cache_dir`.
        """
        self.batch_size = max(1, batch_size)
        """Estado de instancia asociado a `batch_size`.
        """
        self._model: SentenceTransformer | None = None
        """Estado de instancia asociado a `_model`.
        """
        self._lock = threading.RLock()
        """Estado de instancia asociado a `_lock`.
        """
        self._warmed = False
        """Estado de instancia asociado a `_warmed`.
        """

    def _load(self) -> SentenceTransformer:
        """Ejecuta el paso interno `_load`.

        Returns:
            SentenceTransformer: Resultado producido por la operación.

        Throws:
            RuntimeError: Si el estado de ejecución impide completar la operación.
        """
        from sentence_transformers import SentenceTransformer

        with self._lock:
            if self._model is not None:
                return self._model
            source = self.registered.hf_repository
            revision: str | None = self.registered.hf_revision
            if self.registered.artifact_path:
                artifact = Path(self.registered.artifact_path)
                if not artifact.exists():
                    raise RuntimeError("model_artifact_missing")
                source = str(artifact)
                revision = None
            self._model = SentenceTransformer(
                source,
                revision=revision,
                device=self.device,
                cache_folder=self.cache_dir,
                trust_remote_code=False,
                local_files_only=bool(self.registered.artifact_path),
            )
            actual = self._model.get_embedding_dimension()
            if actual != self.registered.dimensions:
                self._model = None
                raise RuntimeError(
                    f"embedding_dimension_mismatch:{actual}:{self.registered.dimensions}"
                )
            return self._model

    def encode_query(self, query: str) -> list[float]:
        """Ejecuta `encode_query` dentro de `EmbeddingRuntime`.

        Args:
            query (str): Valor de `query` utilizado por la operación.

        Returns:
            list[float]: Colección de elementos obtenidos por la operación.
        """
        return self._encode([self.registered.query_prefix + query])[0].tolist()

    def load(self) -> None:
        """Ejecuta `load` dentro de `EmbeddingRuntime`.
        """
        self._load()

    def warmup(self) -> None:
        """Ejecuta `warmup` dentro de `EmbeddingRuntime`.
        """
        with self._lock:
            if self._warmed:
                return
            self._encode([self.registered.query_prefix + "healthcheck"])

    def encode_queries(self, queries: list[str]) -> list[list[float]]:
        """Ejecuta `encode_queries` dentro de `EmbeddingRuntime`.

        Args:
            queries (list[str]): Valor de `queries` utilizado por la operación.

        Returns:
            list[list[float]]: Colección de elementos obtenidos por la operación.
        """
        prefixed = [self.registered.query_prefix + query for query in queries]
        return self._encode(prefixed).tolist()

    def encode_documents(self, documents: list[str]) -> list[list[float]]:
        """Ejecuta `encode_documents` dentro de `EmbeddingRuntime`.

        Args:
            documents (list[str]): Colección de documentos que debe procesarse.

        Returns:
            list[list[float]]: Colección de elementos obtenidos por la operación.
        """
        prefixed = [self.registered.passage_prefix + document for document in documents]
        return self._encode(prefixed).tolist()

    def _encode(self, values: list[str]) -> np.ndarray:
        """Ejecuta el paso interno `_encode`.

        Args:
            values (list[str]): Valor de `values` utilizado por la operación.

        Returns:
            np.ndarray: Resultado producido por la operación.
        """
        with self._lock:
            model = self._load()
            encoded = model.encode(
                values,
                batch_size=min(self.batch_size, len(values)),
                convert_to_numpy=True,
                normalize_embeddings=True,
                show_progress_bar=False,
            )
            self._warmed = True
        return np.asarray(encoded, dtype=np.float32)


def vector_literal(values: list[float]) -> str:
    """Ejecuta la operación `vector_literal`.

    Args:
        values (list[float]): Valor de `values` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    return "[" + ",".join(format(float(value), ".9g") for value in values) + "]"
