"""Implementa las responsabilidades del módulo `playwright_fallback`.
"""
from __future__ import annotations

import re

from app.core.config import Settings
from app.scraper.candidates import URL_PATTERN, InstallerCandidate, extract_candidates

DOWNLOAD_CONTROL_PATTERN = re.compile(
    r"download|descargar|instalador|installer|setup|install|herunterladen|"
    "t[e\u00e9]l[e\u00e9]charger|scarica|baixar|pobierz|"
    "\u0441\u043a\u0430\u0447\u0430\u0442\u044c|\u4e0b\u8f7d|"
    "\u30c0\u30a6\u30f3\u30ed\u30fc\u30c9",
    re.I,
)
"""Constante que define `DOWNLOAD_CONTROL_PATTERN`.
"""
WINDOWS_DESKTOP_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)
"""Constante que define `WINDOWS_DESKTOP_USER_AGENT`.
"""


class PlaywrightCandidateCollector:
    """Representa el componente `PlaywrightCandidateCollector`.
    """
    def __init__(self, settings: Settings) -> None:
        """Inicializa una instancia de `PlaywrightCandidateCollector`.

        Args:
            settings (Settings): Configuración del servicio.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """

    async def collect(self, url: str) -> list[InstallerCandidate]:
        """Ejecuta `collect` dentro de `PlaywrightCandidateCollector`.

        Args:
            url (str): URL del recurso que debe procesarse.

        Returns:
            list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
        """
        try:
            from playwright.async_api import async_playwright
        except ImportError:
            return []

        collected: dict[str, InstallerCandidate] = {}
        try:
            async with async_playwright() as playwright:
                browser = await playwright.chromium.launch(headless=True)
                # Los embudos de descarga suelen mostrar el instalador solo a clientes de
                # escritorio compatibles. Esto emula un navegador Windows neutro para
                # revelar la ruta sin filtrar recursos de otras plataformas.
                context = await browser.new_context(
                    accept_downloads=False,
                    locale="en-US",
                    user_agent=WINDOWS_DESKTOP_USER_AGENT,
                )
                page = await context.new_page()

                page.on(
                    "request",
                    lambda request: collect_url(
                        collected,
                        request.url,
                        "playwright_request",
                        referer=url,
                    ),
                )
                page.on(
                    "download",
                    lambda download: collect_url(
                        collected,
                        download.url,
                        "playwright_download",
                        referer=page.url,
                    ),
                )

                try:
                    await page.goto(
                        url,
                        wait_until="domcontentloaded",
                        timeout=self.settings.playwright_timeout_ms,
                    )
                    await collect_page_candidates(collected, page, url)

                    # Vuelve a explorar después de cada interacción. Muchos sitios abren
                    # primero un diálogo AJAX y después muestran las rutas reales de descarga.
                    seen_controls: set[str] = set()
                    for _depth in range(3):
                        locator = page.locator("a, button, [role=button]").filter(
                            has_text=DOWNLOAD_CONTROL_PATTERN
                        )
                        handles = await locator.element_handles()
                        clicked = 0
                        for handle in handles:
                            if clicked >= 4:
                                break
                            try:
                                if not await handle.is_visible():
                                    continue
                                fingerprint = await control_fingerprint(handle)
                                if fingerprint in seen_controls:
                                    continue
                                seen_controls.add(fingerprint)
                                # El clic del DOM evita que los reintentos queden bloqueados
                                # por capas de consentimiento mientras el timeout exterior
                                # de la página cierra el contexto.
                                await handle.evaluate("element => element.click()")
                                clicked += 1
                                await page.wait_for_timeout(700)
                                await collect_page_candidates(collected, page, page.url)
                                if any(candidate.extension for candidate in collected.values()):
                                    break
                            except Exception:
                                continue
                        if clicked == 0 or any(
                            candidate.extension for candidate in collected.values()
                        ):
                            break
                except Exception:
                    pass
                finally:
                    await context.close()
                    await browser.close()
        except Exception:
            return []

        return list(collected.values())


async def control_fingerprint(handle) -> str:
    """Ejecuta la operación `control_fingerprint`.

    Args:
        handle (Any): Valor de `handle` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    return await handle.evaluate(
        """element => [
            element.tagName,
            (element.innerText || element.textContent || '').trim(),
            element.getAttribute('href') || '',
            element.getAttribute('onclick') || '',
            element.className || ''
        ].join('|')"""
    )


def collect_url(
    collected: dict[str, InstallerCandidate],
    url: str,
    source: str,
    *,
    referer: str | None = None,
) -> None:
    """Ejecuta la operación `collect_url`.

    Args:
        collected (dict[str, InstallerCandidate]): Valor de `collected` utilizado por la operación.
        url (str): URL del recurso que debe procesarse.
        source (str): Fuente de descarga sobre la que se actúa.
        referer (str | None): Valor de `referer` utilizado por la operación.
    """
    if URL_PATTERN.search(url) or re.search(r"/(?:download|installer|setup)(?:[/?]|$)", url, re.I):
        collected.setdefault(url, InstallerCandidate(url=url, source=source, referer=referer))


async def collect_page_candidates(collected, page, base_url: str) -> None:
    """Ejecuta la operación `collect_page_candidates`.

    Args:
        collected (Any): Valor de `collected` utilizado por la operación.
        page (Any): Número de página solicitado.
        base_url (str): Dirección de `base` que debe procesarse.
    """
    html = await page.content()
    for candidate in extract_candidates(html, base_url):
        collected.setdefault(candidate.url, candidate)
