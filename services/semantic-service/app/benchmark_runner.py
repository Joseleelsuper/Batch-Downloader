from __future__ import annotations

import argparse
import json

from app.admin_benchmark import run_admin_benchmark


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--operation-id", required=True)
    parser.add_argument("--model-id", action="append", required=True)
    arguments = parser.parse_args()
    result = run_admin_benchmark(
        operation_id=arguments.operation_id,
        model_ids=arguments.model_id,
    )
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
