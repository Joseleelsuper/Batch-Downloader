"""Implementa las responsabilidades del módulo `model_registry`.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass


@dataclass(frozen=True)
class ModelDefinition:
    """Representa el componente `ModelDefinition`.
    """
    key: str
    """Atributo de clase `key` de `ModelDefinition`.
    """
    repository: str
    """Atributo de clase `repository` de `ModelDefinition`.
    """
    revision: str
    """Atributo de clase `revision` de `ModelDefinition`.
    """
    dimensions: int
    """Atributo de clase `dimensions` de `ModelDefinition`.
    """
    query_prefix: str
    """Atributo de clase `query_prefix` de `ModelDefinition`.
    """
    passage_prefix: str
    """Atributo de clase `passage_prefix` de `ModelDefinition`.
    """

    @property
    def zero_shot_version(self) -> str:
        """Ejecuta `zero_shot_version` dentro de `ModelDefinition`.

        Returns:
            str: Resultado producido por la operación.
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
"""Constante que define `MODEL_DEFINITIONS`.
"""

MODELS_BY_KEY = {model.key: model for model in MODEL_DEFINITIONS}
"""Constante que define `MODELS_BY_KEY`.
"""
MODELS_BY_VERSION = {model.zero_shot_version: model for model in MODEL_DEFINITIONS}
"""Constante que define `MODELS_BY_VERSION`.
"""


def model_index_name(model_version: str) -> str:
    """Ejecuta la operación `model_index_name`.

    Args:
        model_version (str): Valor de `model_version` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    digest = hashlib.sha256(model_version.encode("utf-8")).hexdigest()[:16]
    return f"ix_embeddings_{digest}_hnsw"


def local_model_identity(repository: str, revision: str) -> tuple[str, str]:
    """Construye la identidad estable de un artefacto local.

    Args:
        repository (str): Valor de `repository` utilizado por la operación.
        revision (str): Valor de `revision` utilizado por la operación.

    Returns:
        tuple[str, str]: Resultado producido por la operación.
    """

    model_key = repository.replace("/", "--")
    return model_key, f"{model_key}@{revision}:zero-shot"
