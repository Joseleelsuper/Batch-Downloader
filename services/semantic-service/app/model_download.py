"""Implementa las responsabilidades del módulo `model_download`.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from huggingface_hub import snapshot_download


def main() -> None:
    """Ejecuta el punto de entrada del módulo.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--staging", required=True)
    arguments = parser.parse_args()
    staging = Path(arguments.staging)
    snapshot_download(
        repo_id=arguments.repository,
        revision=arguments.revision,
        local_dir=staging,
        ignore_patterns=["*.bin", "*.pkl", "*.pickle", "*.pt", "*.pth", "*.py"],
        max_workers=1,
        token=False,
    )
    print(json.dumps({"path": str(staging), "revision": arguments.revision}))


if __name__ == "__main__":
    main()
