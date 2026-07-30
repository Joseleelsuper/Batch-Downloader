"""Implementa las responsabilidades del módulo `model_admin`.
"""
from __future__ import annotations

import argparse
import json

from app.config import get_settings
from app.database import Database
from app.store import SemanticStore


def main() -> None:
    """Ejecuta el punto de entrada del módulo.
    """
    parser = argparse.ArgumentParser(
        description="Promueve o restaura versiones semánticas con cobertura completa"
    )
    subcommands = parser.add_subparsers(dest="command", required=True)
    activate = subcommands.add_parser("activate")
    activate.add_argument("model_version")
    activate.add_argument("--rrf-weight", type=float)
    arguments = parser.parse_args()

    database = Database(get_settings())
    database.open()
    database.migrate()
    try:
        if arguments.command == "activate":
            result = SemanticStore(database).activate_complete_model(
                arguments.model_version,
                rrf_weight=arguments.rrf_weight,
            )
            print(json.dumps(result, indent=2))
    finally:
        database.close()


if __name__ == "__main__":
    main()
