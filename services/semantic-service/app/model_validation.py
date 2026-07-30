"""Implementa las responsabilidades del módulo `model_validation`.
"""
from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path

import numpy as np
import psutil


def validate_model(
    path: Path,
    *,
    query_prefix: str,
    passage_prefix: str,
    device: str,
) -> dict[str, object]:
    """Valida la operación `model`.

    Args:
        path (Path): Ruta del recurso que debe procesarse.
        query_prefix (str): Valor de `query_prefix` utilizado por la operación.
        passage_prefix (str): Valor de `passage_prefix` utilizado por la operación.
        device (str): Valor de `device` utilizado por la operación.

    Returns:
        dict[str, object]: Mapa con los datos producidos por la operación.

    Throws:
        RuntimeError: Si el estado de ejecución impide completar la operación.
    """
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    from sentence_transformers import SentenceTransformer

    started = time.perf_counter()
    model = SentenceTransformer(
        str(path),
        device=device,
        trust_remote_code=False,
        local_files_only=True,
    )
    dimensions = int(model.get_embedding_dimension())
    if not 1 <= dimensions <= 2000:
        raise RuntimeError(f"unsupported_hnsw_dimensions:{dimensions}")
    probes = [
        query_prefix + "gestor de contraseñas para Linux",
        query_prefix + "open source code editor",
        passage_prefix + "Aplicación para gestionar contraseñas de forma segura.",
        passage_prefix + "A fast and extensible source code editor.",
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
    if encoded.shape != (len(probes), dimensions):
        raise RuntimeError("embedding_shape_is_not_stable")
    if not np.isfinite(encoded).all():
        raise RuntimeError("embedding_contains_non_finite_values")
    return {
        "dimensions": dimensions,
        "probeCount": len(probes),
        "warmupMs": (time.perf_counter() - started) * 1000,
        "rssBytes": psutil.Process(os.getpid()).memory_info().rss,
    }


def main() -> None:
    """Ejecuta el punto de entrada del módulo.

    Throws:
        SystemExit: Si no puede completarse la operación bajo las condiciones requeridas.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--path", required=True)
    parser.add_argument("--query-prefix", default="")
    parser.add_argument("--passage-prefix", default="")
    parser.add_argument("--device", default="cpu")
    arguments = parser.parse_args()
    try:
        result = validate_model(
            Path(arguments.path),
            query_prefix=arguments.query_prefix,
            passage_prefix=arguments.passage_prefix,
            device=arguments.device,
        )
        print(json.dumps(result, sort_keys=True))
    except Exception as exception:
        reason = "".join(
            char if char.isalnum() or char == "_" else "_"
            for char in str(exception).split(":", 1)[0].lower()
        ).strip("_")
        print(json.dumps({
            "errorCode": (
                "semantic_model_incompatible_"
                + (reason[:70] or "validation_failed")
            )
        }))
        raise SystemExit(2) from None


if __name__ == "__main__":
    main()
