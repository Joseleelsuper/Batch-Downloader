"""Define revisiones reproducibles de modelos base y nombres estables para artefactos e índices
HNSW.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass


@dataclass(frozen=True)
class ModelDefinition:
    """Describe un modelo base antes de prepararlo o entrenarlo, con revisión fija y prefijos de
    codificación.

    Attributes:
        key: Nombre corto utilizado en configuración y versiones de modelo.
        repository: Repositorio de origen de sus archivos.
        revision: Commit exacto que evita cambios implícitos de pesos.
        dimensions: Número de componentes de cada vector de embeddings.
        query_prefix: Prefijo que se añade a consultas antes de codificarlas.
        passage_prefix: Prefijo que se añade a documentos antes de codificarlos.

    See Also:
        app.embeddings.RegisteredModel: Identidad y configuración de un modelo persistido.
    """
    key: str

    repository: str

    revision: str

    dimensions: int

    query_prefix: str

    passage_prefix: str


    @property
    def zero_shot_version(self) -> str:
        """Identifica la variante sin entrenamiento del modelo base y su revisión fija.

        Returns:
            identificador con formato clave@revisión:zero-shot.
        """
        return f"{self.key}@{self.revision}:zero-shot"


MODEL_DEFINITIONS = (
    ModelDefinition(
        key="paraphrase-multilingual-MiniLM-L12-v2",
        repository="sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
        revision="e8f8c211226b894fcb81acc59f3b34ba3efd5f42",
        dimensions=384,
        query_prefix="",
        passage_prefix="",
    ),
    ModelDefinition(
        key="multilingual-e5-base",
        repository="intfloat/multilingual-e5-base",
        revision="d128750597153bb5987e10b1c3493a34e5a4502a",
        dimensions=768,
        query_prefix="query: ",
        passage_prefix="passage: ",
    ),
    ModelDefinition(
        key="bge-m3",
        repository="BAAI/bge-m3",
        revision="5617a9f61b028005a4858fdac845db406aefb181",
        dimensions=1024,
        query_prefix="",
        passage_prefix="",
    ),
)


MODELS_BY_KEY = {model.key: model for model in MODEL_DEFINITIONS}

MODELS_BY_VERSION = {model.zero_shot_version: model for model in MODEL_DEFINITIONS}



def model_index_name(model_version: str) -> str:
    """Deriva un nombre SQL corto y estable para el índice HNSW de una versión de modelo.

    Args:
        model_version: Identidad inmutable del modelo, incluida su revisión y variante de
            entrenamiento.

    Returns:
        nombre ix_embeddings_<16 caracteres de SHA-256>_hnsw, sin interpolar el identificador
            original.
    """
    digest = hashlib.sha256(model_version.encode("utf-8")).hexdigest()[:16]
    return f"ix_embeddings_{digest}_hnsw"


def local_model_identity(repository: str, revision: str) -> tuple[str, str]:
    """Construye clave y versión estables a partir del repositorio y su revisión resuelta.

    Args:
        repository: Repositorio de origen del artefacto.
        revision: Revisión fija del repositorio que identifica sus archivos.

    Returns:
        par clave/versión; las barras del repositorio se sustituyen por dos guiones.
    """

    model_key = repository.replace("/", "--")
    return model_key, f"{model_key}@{revision}:zero-shot"
