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
    model_version: str
    artifact_id: str | None
    model_key: str
    hf_repository: str
    hf_revision: str
    dimensions: int
    query_prefix: str
    passage_prefix: str
    artifact_path: str | None = None
    rrf_weight: float = 1.0
    minimum_similarity: float = 0.0

    @classmethod
    def from_row(cls, row: dict[str, Any]) -> RegisteredModel:
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
    """Loads one immutable model version and serializes access to its backend."""

    def __init__(
        self,
        registered: RegisteredModel,
        *,
        device: str,
        cache_dir: str,
        batch_size: int = 32,
    ) -> None:
        self.registered = registered
        self.device = device
        self.cache_dir = cache_dir
        self.batch_size = max(1, batch_size)
        self._model: SentenceTransformer | None = None
        self._lock = threading.RLock()
        self._warmed = False

    def _load(self) -> SentenceTransformer:
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
        return self._encode([self.registered.query_prefix + query])[0].tolist()

    def load(self) -> None:
        self._load()

    def warmup(self) -> None:
        with self._lock:
            if self._warmed:
                return
            self._encode([self.registered.query_prefix + "healthcheck"])

    def encode_queries(self, queries: list[str]) -> list[list[float]]:
        prefixed = [self.registered.query_prefix + query for query in queries]
        return self._encode(prefixed).tolist()

    def encode_documents(self, documents: list[str]) -> list[list[float]]:
        prefixed = [self.registered.passage_prefix + document for document in documents]
        return self._encode(prefixed).tolist()

    def _encode(self, values: list[str]) -> np.ndarray:
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
    return "[" + ",".join(format(float(value), ".9g") for value in values) + "]"
