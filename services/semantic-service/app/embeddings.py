"""Carga un unico modelo local y genera embeddings normalizados."""
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
    """Configuracion persistida que debe coincidir con el manifiesto local."""

    model_version: str
    dimensions: int
    query_prefix: str
    passage_prefix: str
    minimum_similarity: float

    @classmethod
    def from_row(cls, row: dict[str, Any]) -> RegisteredModel:
        return cls(
            model_version=str(row["model_version"]),
            dimensions=int(row["dimensions"]),
            query_prefix=str(row["query_prefix"] or ""),
            passage_prefix=str(row["passage_prefix"] or ""),
            minimum_similarity=float(row.get("minimum_similarity") or 0.0),
        )


class EmbeddingRuntime:
    """Mantiene una instancia de SentenceTransformer cargada solo desde disco local."""

    def __init__(
        self,
        registered: RegisteredModel,
        *,
        model_dir: str,
        cache_dir: str,
        device: str,
        batch_size: int = 32,
    ) -> None:
        self.registered = registered
        self.model_dir = model_dir
        self.cache_dir = cache_dir
        self.device = device
        self.batch_size = max(1, batch_size)
        self._model: SentenceTransformer | None = None
        self._lock = threading.RLock()
        self._warmed = False

    def _load(self) -> SentenceTransformer:
        from sentence_transformers import SentenceTransformer

        with self._lock:
            if self._model is not None:
                return self._model
            path = Path(self.model_dir)
            if not path.is_dir():
                raise RuntimeError("model_artifact_missing")
            self._model = SentenceTransformer(
                str(path),
                device=self.device,
                cache_folder=self.cache_dir,
                trust_remote_code=False,
                local_files_only=True,
            )
            actual = int(self._model.get_embedding_dimension())
            if actual != self.registered.dimensions:
                self._model = None
                raise RuntimeError(
                    f"embedding_dimension_mismatch:{actual}:{self.registered.dimensions}"
                )
            return self._model

    def load(self) -> None:
        """Carga los pesos y comprueba sus dimensiones."""
        self._load()

    def warmup(self) -> None:
        """Ejecuta una codificacion pequena de salud una sola vez."""
        with self._lock:
            if not self._warmed:
                self._encode([self.registered.query_prefix + "healthcheck"])

    def encode_query(self, query: str) -> list[float]:
        return self._encode([self.registered.query_prefix + query])[0].tolist()

    def encode_documents(self, documents: list[str]) -> list[list[float]]:
        values = [self.registered.passage_prefix + value for value in documents]
        return self._encode(values).tolist()

    def _encode(self, values: list[str]) -> np.ndarray:
        if not values:
            return np.empty((0, self.registered.dimensions), dtype=np.float32)
        with self._lock:
            encoded = self._load().encode(
                values,
                batch_size=min(self.batch_size, len(values)),
                convert_to_numpy=True,
                normalize_embeddings=True,
                show_progress_bar=False,
            )
            self._warmed = True
        return np.asarray(encoded, dtype=np.float32)


def vector_literal(values: list[float]) -> str:
    """Serializa un vector para pasarlo como parametro de pgvector."""
    return "[" + ",".join(format(float(value), ".9g") for value in values) + "]"
