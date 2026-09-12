"""Carga modelos locales de SentenceTransformer y produce vectores normalizados para consultas y
documentos.
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
    """Conserva la identidad y configuración persistidas que permiten reproducir la codificación
    de un índice.

    Attributes:
        model_version: Identidad de modelo, revisión y variante de entrenamiento.
        artifact_id: UUID del artefacto local o None en registros históricos.
        model_key: Clave corta de la familia de modelo.
        hf_repository: Repositorio del que proceden los archivos.
        hf_revision: Revisión fija de los pesos.
        dimensions: Número de componentes de cada vector.
        query_prefix: Texto antepuesto a consultas.
        passage_prefix: Texto antepuesto a documentos.
        artifact_path: Directorio local; None impide cargar el modelo.
        rrf_weight: Peso del modelo para la fusión de rankings.
        minimum_similarity: Similitud mínima registrada para aceptar candidatos.

    See Also:
        app.store.SemanticStore.active_model: Resuelve la identidad activa con índice
            completo.
    """
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
        """Convierte una fila persistida, normalizando UUID y valores numéricos y aplicando los
        valores históricos por defecto.

        Args:
            row: Fila de PostgreSQL con las columnas requeridas por la proyección.

        Returns:
            identidad inmutable; rrf_weight vacío o cero toma 1.0 y minimum_similarity vacío
                toma 0.0.
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
    """Comparte una instancia local del modelo y serializa carga, calentamiento y codificación
    mediante un bloqueo reentrante.
    La construcción no carga pesos. La primera codificación valida las dimensiones y evita
    ejecutar código remoto.

    See Also:
        RegisteredModel: Configuración que debe corresponder al índice consultado.
    """

    def __init__(
        self,
        registered: RegisteredModel,
        *,
        device: str,
        cache_dir: str,
        batch_size: int = 32,
    ) -> None:
        """Conserva la configuración de carga diferida y normaliza el tamaño mínimo del lote a
        uno.

        Args:
            registered: Identidad, dimensiones, prefijos y ruta local del modelo que se va a
                codificar.
            device: Dispositivo de ejecución aceptado por SentenceTransformer, como cpu.
            cache_dir: Directorio local de caché; la carga nunca descarga archivos remotos.
            batch_size: Máximo de textos por lote de codificación; se eleva a uno si es menor.
        """
        self.registered = registered

        self.device = device

        self.cache_dir = cache_dir

        self.batch_size = max(1, batch_size)

        self._model: SentenceTransformer | None = None

        self._lock = threading.RLock()

        self._warmed = False


    def _load(self) -> SentenceTransformer:
        """Carga una sola vez los pesos desde el directorio local y comprueba que producen las
        dimensiones registradas.

        Returns:
            modelo reutilizable protegido por el mismo bloqueo de codificación.

        Raises:
            RuntimeError: Si falta el directorio local o las dimensiones reales no coinciden
                con las registradas.
        """
        from sentence_transformers import SentenceTransformer

        with self._lock:
            if self._model is not None:
                return self._model
            if not self.registered.artifact_path:
                raise RuntimeError("model_artifact_missing")
            artifact = Path(self.registered.artifact_path)
            if not artifact.is_dir():
                raise RuntimeError("model_artifact_missing")
            self._model = SentenceTransformer(
                str(artifact),
                device=self.device,
                cache_folder=self.cache_dir,
                trust_remote_code=False,
                local_files_only=True,
            )
            actual = self._model.get_embedding_dimension()
            if actual != self.registered.dimensions:
                self._model = None
                raise RuntimeError(
                    f"embedding_dimension_mismatch:{actual}:{self.registered.dimensions}"
                )
            return self._model

    def encode_query(self, query: str) -> list[float]:
        """Codifica la consulta con el prefijo configurado en el mismo espacio vectorial del
        índice de documentos.

        Args:
            query: Texto de consulta sin prefijo; se antepone el configurado en el modelo.

        Returns:
            vector normalizado de la consulta, convertido a lista de float.
        """
        return self._encode([self.registered.query_prefix + query])[0].tolist()

    def load(self) -> None:
        """Prepara los pesos locales sin ejecutar una consulta ni marcar el runtime como
        calentado.
        """
        self._load()

    def warmup(self) -> None:
        """Codifica una consulta de salud una sola vez; cualquier codificación previa ya
        satisface el calentamiento.
        """
        with self._lock:
            if self._warmed:
                return
            self._encode([self.registered.query_prefix + "healthcheck"])

    def encode_queries(self, queries: list[str]) -> list[list[float]]:
        """Añade el prefijo de consulta a cada entrada y codifica todas con la misma instancia
        del modelo.

        Args:
            queries: Consultas sin prefijo, en el orden en que deben devolverse los vectores.

        Returns:
            vectores normalizados en el orden original de las consultas.
        """
        prefixed = [self.registered.query_prefix + query for query in queries]
        return self._encode(prefixed).tolist()

    def encode_documents(self, documents: list[str]) -> list[list[float]]:
        """Añade el prefijo de pasaje de cada documento antes de codificarlo para el índice.

        Args:
            documents: Textos de documentos sin prefijo, en el orden de entrada.

        Returns:
            vectores normalizados en el orden original de los documentos.
        """
        prefixed = [self.registered.passage_prefix + document for document in documents]
        return self._encode(prefixed).tolist()

    def _encode(self, values: list[str]) -> np.ndarray:
        """Serializa la codificación en lotes, normaliza los embeddings y marca el runtime como
        calentado al terminar.

        Args:
            values: Textos ya prefijados; el llamador conserva su correspondencia con
                consultas o documentos.

        Returns:
            matriz NumPy float32 con un vector por texto de entrada.
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
    """Serializa componentes numéricos con nueve cifras significativas en la sintaxis de entrada
    de pgvector.

    Args:
        values: Componentes del vector, en el mismo orden que espera la columna vector de
            PostgreSQL.

    Returns:
        literal entre corchetes para pasarlo como parámetro SQL, sin interpolarlo en la
            consulta.
    """
    return "[" + ",".join(format(float(value), ".9g") for value in values) + "]"
