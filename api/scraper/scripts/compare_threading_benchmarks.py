"""Proporciona la utilidad de línea de comandos `compare_threading_benchmarks`.
"""
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def rows_by_key(report: dict) -> dict[tuple[str, int], dict]:
    """Ejecuta la operación `rows_by_key`.

    Args:
        report (dict): Valor de `report` utilizado por la operación.

    Returns:
        dict[tuple[str, int], dict]: Mapa con los datos producidos por la operación.
    """
    return {
        (row["workload"], int(row["threads"])): row
        for row in report["measurements"]
    }


def main() -> None:
    """Ejecuta el punto de entrada del módulo.

    Throws:
        RuntimeError: Si el estado de ejecución impide completar la operación.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--standard", type=Path, required=True)
    parser.add_argument("--free-threaded", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    arguments = parser.parse_args()
    standard = json.loads(arguments.standard.read_text(encoding="utf-8"))
    free_threaded = json.loads(arguments.free_threaded.read_text(encoding="utf-8"))
    if standard["freeThreadedBuild"] or not standard["gilEnabled"]:
        raise RuntimeError("standard_control_runtime_invalid")
    if not free_threaded["freeThreadedBuild"] or free_threaded["gilEnabled"]:
        raise RuntimeError("free_threaded_runtime_invalid")
    if standard["databaseDriver"] != free_threaded["databaseDriver"]:
        raise RuntimeError("benchmark_driver_mismatch")
    standard_rows = rows_by_key(standard)
    free_rows = rows_by_key(free_threaded)
    if standard_rows.keys() != free_rows.keys():
        raise RuntimeError("benchmark_workload_mismatch")
    comparisons = []
    for workload, threads in sorted(standard_rows):
        baseline = standard_rows[(workload, threads)]
        candidate = free_rows[(workload, threads)]
        if baseline["checksums"] != candidate["checksums"]:
            raise RuntimeError("benchmark_result_mismatch")
        standard_throughput = float(baseline["medianThroughput"])
        free_throughput = float(candidate["medianThroughput"])
        comparisons.append(
            {
                "workload": workload,
                "threads": threads,
                "standardThroughput": standard_throughput,
                "freeThreadedThroughput": free_throughput,
                "speedup": (
                    free_throughput / standard_throughput
                    if standard_throughput
                    else 0.0
                ),
            }
        )
    arguments.output_dir.mkdir(parents=True, exist_ok=True)
    payload = {
        "standard": standard["python"],
        "freeThreaded": free_threaded["python"],
        "databaseDriver": standard["databaseDriver"],
        "tasks": standard["tasks"],
        "repetitions": standard["repetitions"],
        "comparisons": comparisons,
    }
    (arguments.output_dir / "python314-comparison.json").write_text(
        json.dumps(payload, indent=2) + "\n",
        encoding="utf-8",
    )
    with (arguments.output_dir / "python314-comparison.csv").open(
        "w",
        newline="",
        encoding="utf-8",
    ) as output:
        writer = csv.DictWriter(output, fieldnames=list(comparisons[0]))
        writer.writeheader()
        writer.writerows(comparisons)
    markdown = [
        "# Benchmark CPython 3.14 estándar frente a 3.14t",
        "",
        f"- Repeticiones por configuración: {standard['repetitions']}",
        f"- Driver: `{standard['databaseDriver']}`",
        "",
        "| Carga | Hilos | Throughput estándar | Throughput 3.14t | Aceleración |",
        "|---|---:|---:|---:|---:|",
    ]
    markdown.extend(
        "| {workload} | {threads} | {standardThroughput:.2f} | "
        "{freeThreadedThroughput:.2f} | {speedup:.3f}x |".format(**row)
        for row in comparisons
    )
    (arguments.output_dir / "python314-comparison.md").write_text(
        "\n".join(markdown) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(payload, indent=2))


if __name__ == "__main__":
    main()
