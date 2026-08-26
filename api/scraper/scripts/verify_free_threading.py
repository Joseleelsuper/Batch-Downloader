"""Proporciona la utilidad de línea de comandos `verify_free_threading`.
"""
from __future__ import annotations

import concurrent.futures
import json
import sys
import sysconfig

from playwright.sync_api import sync_playwright

from app.core.free_threading import assert_free_threaded_runtime
from app.scraper.candidates import extract_candidates


def parse_fixture(html: str) -> int:
    """Analiza la operación `fixture`.

    Args:
        html (str): Valor de `html` utilizado por la operación.

    Returns:
        int: Resultado producido por la operación.
    """
    return len(extract_candidates(html, "https://verification.invalid"))


def main() -> None:
    """Ejecuta el punto de entrada del módulo.

    Throws:
        RuntimeError: Si el estado de ejecución impide completar la operación.
    """
    assert_free_threaded_runtime()
    html = "<html><body><a href='/app.exe'>Descargar</a></body></html>"
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
        results = list(executor.map(parse_fixture, [html] * 200))
    if results != [1] * 200:
        raise RuntimeError("threaded_parser_result_mismatch")
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(args=["--no-sandbox"])
        page = browser.new_page()
        page.set_content(html)
        if page.locator("a").count() != 1:
            raise RuntimeError("chromium_smoke_check_failed")
        browser.close()
    assert_free_threaded_runtime()
    print(
        json.dumps(
            {
                "python": sys.version,
                "Py_GIL_DISABLED": sysconfig.get_config_var("Py_GIL_DISABLED"),
                "gilEnabled": sys._is_gil_enabled(),
                "concurrentParses": len(results),
                "chromium": "ok",
            }
        )
    )


if __name__ == "__main__":
    main()
