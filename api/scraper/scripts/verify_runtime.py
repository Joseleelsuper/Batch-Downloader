"""Comprueba el parseo concurrente y el arranque de Chromium en la imagen del scraper."""
from __future__ import annotations

import concurrent.futures
import json
import sys

from playwright.sync_api import sync_playwright

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
    print(
        json.dumps(
            {
                "python": sys.version,
                "concurrentParses": len(results),
                "chromium": "ok",
            }
        )
    )


if __name__ == "__main__":
    main()
