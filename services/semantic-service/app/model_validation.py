"""Valida el modelo intercambiable de ``/models/current`` sin descargar nada."""
from __future__ import annotations

import argparse
import json
import math
import time
from dataclasses import dataclass
from pathlib import Path

import numpy as np

MANIFEST_NAME = "batch-model.json"
FORBIDDEN_SUFFIXES = (".bin", ".pkl", ".pickle", ".pt", ".pth", ".py")


@dataclass(frozen=True)
class ModelDescriptor:
    """Identidad y parametros necesarios para reproducir los embeddings."""

    model_version: str
    dimensions: int
    query_prefix: str
    passage_prefix: str
    minimum_similarity: float

    @classmethod
    def from_manifest(cls, payload: object) -> ModelDescriptor:
        if not isinstance(payload, dict):
            raise RuntimeError("semantic_model_manifest_object_required")
        required = {
            "modelVersion",
            "dimensions",
            "queryPrefix",
            "passagePrefix",
            "minimumSimilarity",
        }
        if set(payload) != required:
            raise RuntimeError("semantic_model_manifest_fields_invalid")
        version = payload["modelVersion"]
        dimensions = payload["dimensions"]
        query_prefix = payload["queryPrefix"]
        passage_prefix = payload["passagePrefix"]
        minimum_similarity = payload["minimumSimilarity"]
        if not isinstance(version, str) or not version.strip() or len(version) > 200:
            raise RuntimeError("semantic_model_manifest_version_invalid")
        if any(char in version for char in "\r\n\x00"):
            raise RuntimeError("semantic_model_manifest_version_invalid")
        if (
            not isinstance(dimensions, int)
            or isinstance(dimensions, bool)
            or not 1 <= dimensions <= 2000
        ):
            raise RuntimeError("semantic_model_manifest_dimensions_invalid")
        if not isinstance(query_prefix, str) or not isinstance(passage_prefix, str):
            raise RuntimeError("semantic_model_manifest_prefix_invalid")
        if len(query_prefix) > 1000 or len(passage_prefix) > 1000:
            raise RuntimeError("semantic_model_manifest_prefix_invalid")
        if not isinstance(minimum_similarity, (int, float)) or isinstance(minimum_similarity, bool):
            raise RuntimeError("semantic_model_manifest_similarity_invalid")
        minimum_similarity = float(minimum_similarity)
        if not math.isfinite(minimum_similarity) or not -1 <= minimum_similarity <= 1:
            raise RuntimeError("semantic_model_manifest_similarity_invalid")
        return cls(
            model_version=version.strip(),
            dimensions=dimensions,
            query_prefix=query_prefix,
            passage_prefix=passage_prefix,
            minimum_similarity=minimum_similarity,
        )


def load_model_manifest(path: Path, *, manifest_name: str = MANIFEST_NAME) -> ModelDescriptor:
    """Lee y valida el manifiesto sin cargar los pesos."""
    directory = path.resolve()
    manifest = directory / manifest_name
    if not directory.is_dir() or not manifest.is_file():
        raise RuntimeError("semantic_model_manifest_missing")
    try:
        payload = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exception:
        raise RuntimeError("semantic_model_manifest_invalid") from exception
    return ModelDescriptor.from_manifest(payload)


def _model_files(path: Path) -> list[Path]:
    return [
        item
        for item in path.rglob("*")
        if item.is_file() and ".cache" not in item.parts
    ]


def model_directory_ready(path: Path, *, manifest_name: str = MANIFEST_NAME) -> bool:
    """Comprueba manifiesto y formato de pesos, sin inicializar PyTorch."""
    try:
        load_model_manifest(path, manifest_name=manifest_name)
        files = _model_files(path)
        has_weights = any(item.suffix.lower() == ".safetensors" for item in files)
        has_forbidden = any(item.suffix.lower() in FORBIDDEN_SUFFIXES for item in files)
        return bool(files) and has_weights and not has_forbidden
    except (OSError, RuntimeError):
        return False


def validate_model_directory(
    path: Path,
    *,
    device: str,
    manifest_name: str = MANIFEST_NAME,
) -> tuple[ModelDescriptor, dict[str, object]]:
    """Carga el modelo local y verifica dimensiones, forma y valores finitos."""
    descriptor = load_model_manifest(path, manifest_name=manifest_name)
    files = _model_files(path)
    if not any(item.suffix.lower() == ".safetensors" for item in files):
        raise RuntimeError("semantic_model_incompatible_safetensors_required")
    if any(item.suffix.lower() in FORBIDDEN_SUFFIXES for item in files):
        raise RuntimeError("semantic_model_incompatible_unsafe_file")
    from sentence_transformers import SentenceTransformer

    started = time.perf_counter()
    model = SentenceTransformer(
        str(path.resolve()),
        device=device,
        trust_remote_code=False,
        local_files_only=True,
    )
    actual = int(model.get_embedding_dimension())
    if actual != descriptor.dimensions:
        raise RuntimeError("semantic_model_dimension_mismatch")
    probes = [
        descriptor.query_prefix + "gestor de contrasenas para Linux",
        descriptor.passage_prefix + "Aplicacion para gestionar contrasenas.",
    ]
    encoded = np.asarray(
        model.encode(
            probes,
            convert_to_numpy=True,
            normalize_embeddings=True,
            show_progress_bar=False,
        ),
        dtype=np.float32,
    )
    if encoded.shape != (len(probes), descriptor.dimensions):
        raise RuntimeError("semantic_model_embedding_shape_invalid")
    if not np.isfinite(encoded).all():
        raise RuntimeError("semantic_model_embedding_non_finite")
    return descriptor, {
        "dimensions": descriptor.dimensions,
        "probeCount": len(probes),
        "warmupMs": (time.perf_counter() - started) * 1000,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Valida el modelo semantico local")
    parser.add_argument("--path", default="/models/current")
    parser.add_argument("--device", default="cpu")
    arguments = parser.parse_args()
    try:
        descriptor, result = validate_model_directory(Path(arguments.path), device=arguments.device)
        print(json.dumps({"modelVersion": descriptor.model_version, **result}, sort_keys=True))
    except Exception as exception:
        reason = str(exception).split(":", 1)[0].lower()
        reason = "".join(char if char.isalnum() or char == "_" else "_" for char in reason)
        error_code = "semantic_model_incompatible_" + (reason[:70] or "validation_failed")
        print(json.dumps({"errorCode": error_code}))
        raise SystemExit(2) from None


if __name__ == "__main__":
    main()
