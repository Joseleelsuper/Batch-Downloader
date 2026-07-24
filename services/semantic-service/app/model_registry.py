from __future__ import annotations

import hashlib
from dataclasses import dataclass


@dataclass(frozen=True)
class ModelDefinition:
    key: str
    repository: str
    revision: str
    dimensions: int
    query_prefix: str
    passage_prefix: str

    @property
    def zero_shot_version(self) -> str:
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
    digest = hashlib.sha256(model_version.encode("utf-8")).hexdigest()[:16]
    return f"ix_embeddings_{digest}_hnsw"
