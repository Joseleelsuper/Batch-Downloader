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
DEFAULT_HTML = Path(__file__).parent / "fixtures" / "benchmark_catalog.html"


def parse_and_score(html: str, iteration: int) -> int:
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
    return parse_and_score(html, iteration)


def controlled_http_workload(url: str, iteration: int) -> int:
    with urllib.request.urlopen(url, timeout=5) as response:
        html = response.read().decode("utf-8")
    return parse_and_score(html, iteration)


def run(
    workload,
    source: str,
    workers: int,
    tasks: int,
) -> dict[str, float | int]:
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
    payload = html.encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - stdlib callback name
            if self.path != "/catalog":
                self.send_error(404)
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, _format: str, *args: object) -> None:
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
