"""Proporciona la utilidad de línea de comandos `benchmark_free_threading`.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import statistics
import sys
import sysconfig
import threading
import time
import urllib.request
from collections.abc import Iterator
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from app.scraper.candidates import extract_candidates, score_candidate

THREAD_COUNTS = (1, 2, 4, 8)
"""Constante que define `THREAD_COUNTS`.
"""
DEFAULT_HTML = Path(__file__).parent / "fixtures" / "benchmark_catalog.html"
"""Constante que define `DEFAULT_HTML`.
"""


def parse_and_score(html: str, iteration: int) -> int:
    """Analiza la operación `and_score`.

    Args:
        html (str): Valor de `html` utilizado por la operación.
        iteration (int): Valor de `iteration` utilizado por la operación.

    Returns:
        int: Resultado producido por la operación.
    """
    candidates = extract_candidates(html, "https://benchmark.invalid/")
    scored = [
        score_candidate(
            candidate,
            app_name=f"Benchmark {iteration}",
            package_id=f"Vendor.Benchmark.{iteration}",
        )
        for candidate in candidates
    ]
    return sum(candidate.score for candidate in scored)


def cached_html_workload(html: str, iteration: int) -> int:
    """Mide el análisis repetido de un documento HTML ya cargado en memoria.

    Args:
        html (str): Documento usado como entrada de la extracción.
        iteration (int): Índice que hace único el nombre de la aplicación de prueba.

    Returns:
        int: Suma de puntuaciones de los candidatos extraídos.
    """
    return parse_and_score(html, iteration)


def controlled_http_workload(url: str, iteration: int) -> int:
    """Mide la extracción después de descargar HTML desde el servidor controlado.

    Args:
        url (str): Endpoint local del fixture HTTP.
        iteration (int): Índice que hace único el nombre de la aplicación de prueba.

    Returns:
        int: Suma de puntuaciones de los candidatos extraídos.
    """
    with urllib.request.urlopen(url, timeout=5) as response:
        html = response.read().decode("utf-8")
    return parse_and_score(html, iteration)


def run(
    workload,
    source: str,
    workers: int,
    tasks: int,
) -> dict[str, float | int]:
    """Ejecuta una carga con un número fijo de workers y calcula su throughput.

    Args:
        workload (Any): Función que recibe la fuente y el índice de tarea.
        source (str): HTML o URL que consumirá la función de carga.
        workers (int): Número de hilos del executor.
        tasks (int): Número total de invocaciones.

    Returns:
        dict[str, float | int]: Segundos, throughput, hilos y suma de comprobación.
    """
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as executor:
        checksums = list(
            executor.map(
                lambda index: workload(source, index),
                range(tasks),
            )
        )
    elapsed = time.perf_counter() - started
    return {
        "threads": workers,
        "seconds": elapsed,
        "throughput": tasks / elapsed,
        "checksum": sum(checksums),
    }


@contextmanager
def controlled_http_server(html: str) -> Iterator[str]:
    """Sirve el fixture por HTTP local y lo apaga al salir del contexto.

    Args:
        html (str): Documento que responderá en ``/catalog``.

    Yields:
        Iterator[str]: URL local que devuelve el documento servido.
    """
    payload = html.encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        """Representa el componente `Handler`.
        """
        def do_GET(self) -> None:  # noqa: N802 - nombre exigido por la biblioteca estándar
            """Responde al único recurso del fixture o devuelve HTTP 404."""
            if self.path != "/catalog":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, _format: str, *args: object) -> None:
            """Ejecuta `log_message` dentro de `Handler`.

            Args:
                _format (str): Valor de `_format` utilizado por la operación.
                *args (object): Valor de `args` utilizado por la operación.
            """
            del args

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(
        target=server.serve_forever,
        name="controlled-benchmark-http",
        daemon=True,
    )
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/catalog"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def benchmark_workload(
    *,
    name: str,
    workload,
    source: str,
    tasks: int,
    repetitions: int,
) -> list[dict[str, object]]:
    """Recoge medianas por número de hilos para una carga y sus repeticiones.

    Args:
        name (str): Identificador de la carga en el informe.
        workload (Any): Función que ejecuta una tarea de la carga.
        source (str): Entrada común para todas las tareas.
        tasks (int): Tareas de cada repetición.
        repetitions (int): Número de muestras por configuración.

    Returns:
        list[dict[str, object]]: Medianas, hilos y checksums agrupados por carga.
    """
    measurements: list[dict[str, object]] = []
    for workers in THREAD_COUNTS:
        samples = [
            run(workload, source, workers, tasks)
            for _ in range(repetitions)
        ]
        measurements.append(
            {
                "workload": name,
                "threads": workers,
                "medianSeconds": statistics.median(
                    float(sample["seconds"]) for sample in samples
                ),
                "medianThroughput": statistics.median(
                    float(sample["throughput"]) for sample in samples
                ),
                "checksums": sorted(
                    {int(sample["checksum"]) for sample in samples}
                ),
            }
        )
    return measurements


def verify_checksums(measurements: list[dict[str, object]]) -> None:
    """Exige que cada carga produzca el mismo checksum en todas las configuraciones.

    Args:
        measurements (list[dict[str, object]]): Mediciones agrupadas por carga e hilos.

    Throws:
        RuntimeError: Si una muestra no es repetible o cambia entre configuraciones.
    """
    by_workload: dict[str, set[int]] = {}
    for measurement in measurements:
        checksums = measurement["checksums"]
        if not isinstance(checksums, list) or len(checksums) != 1:
            raise RuntimeError("benchmark_checksum_not_repeatable")
        by_workload.setdefault(str(measurement["workload"]), set()).add(
            int(checksums[0])
        )
    if any(len(values) != 1 for values in by_workload.values()):
        raise RuntimeError("benchmark_checksum_differs_by_thread_count")


def main() -> None:
    """Ejecuta las cargas CPU/HTTP y emite el informe del runtime actual."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--html", type=Path, default=DEFAULT_HTML)
    parser.add_argument("--tasks", type=int, default=200)
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args()
    html = arguments.html.read_text(encoding="utf-8")
    with controlled_http_server(html) as controlled_url:
        measurements = benchmark_workload(
            name="stored-html-cpu",
            workload=cached_html_workload,
            source=html,
            tasks=arguments.tasks,
            repetitions=arguments.repetitions,
        )
        measurements.extend(
            benchmark_workload(
                name="controlled-http",
                workload=controlled_http_workload,
                source=controlled_url,
                tasks=arguments.tasks,
                repetitions=arguments.repetitions,
            )
        )
    verify_checksums(measurements)
    payload = {
        "python": sys.version,
        "freeThreadedBuild": sysconfig.get_config_var("Py_GIL_DISABLED") == 1,
        "gilEnabled": getattr(sys, "_is_gil_enabled", lambda: True)(),
        "databaseDriver": "aiomysql==0.3.2",
        "poolContract": "SQLAlchemy AsyncAdaptedQueuePool; no sessions cross threads",
        "htmlFixture": str(arguments.html),
        "tasks": arguments.tasks,
        "repetitions": arguments.repetitions,
        "measurements": measurements,
    }
    rendered = json.dumps(payload, indent=2)
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
