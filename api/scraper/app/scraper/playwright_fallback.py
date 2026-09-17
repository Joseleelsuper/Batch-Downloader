"""Descubre URLs que solo aparecen tras ejecutar JavaScript o activar controles de descarga
visibles.
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

WINDOWS_DESKTOP_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)



class PlaywrightCandidateCollector:
    """Usa Chromium headless con User-Agent Windows y captura requests, downloads, atributos
    dinámicos y controles de página.
    """

    def __init__(self, settings: Settings) -> None:
        """Conserva límites de Playwright y timeout de navegación.

        Args:
            settings: Configuración de timeouts, redirecciones y límites.
        """
        self.settings = settings


    async def collect(self, url: str) -> list[InstallerCandidate]:
        """Abre una página, recoge URLs dinámicas y cierra browser/context incluso cuando la
        navegación falla.

        Args:
            url: URL que se clasifica como icono.

        Returns:
            candidatos únicos o lista vacía si Playwright no está instalado o falla.
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
                    await explore_download_controls(page, collected)
                except Exception:
                    pass
                finally:
                    await context.close()
                    await browser.close()
        except Exception:
            return []

        return list(collected.values())


async def control_fingerprint(handle) -> str:
    """Calcula una huella estable de etiqueta, texto, href, onclick y clase para no hacer clic
    dos veces.

    Args:
        handle: Elemento Playwright que se examina o pulsa.

    Returns:
        huella textual.
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
    """Añade una URL cuando parece descarga por extensión o ruta de download/installer/setup y
    conserva el Referer.

    Args:
        collected: Mapa mutable de candidatos indexados por URL.
        url: URL que se clasifica como icono.
        source: Procedencia que se conserva en IconResult.
        referer: Página que originó la petición o descarga.
    """
    if URL_PATTERN.search(url) or re.search(r"/(?:download|installer|setup)(?:[/?]|$)", url, re.I):
        collected.setdefault(url, InstallerCandidate(url=url, source=source, referer=referer))


async def collect_page_candidates(collected, page, base_url: str) -> None:
    """Captura data-release-url firmado de SourceForge y combina extracción HTML genérica.

    Args:
        collected: Mapa mutable de candidatos indexados por URL.
        page: Página Playwright cuyo DOM se analiza.
        base_url: URL base para resolver referencias relativas.
    """
    # SourceForge expone el mirror actual mediante una URL firmada y efímera en
    # ``data-release-url``. No aparece como ``href`` y, por tanto, el extractor
    # HTML genérico no puede verla. La URL se usa únicamente para atestar los
    # bytes; ``referer`` conserva la ruta estable que se publicará en catálogo.
    try:
        release_urls = await page.locator("[data-release-url]").evaluate_all(
            "elements => elements.map(element => "
            "element.getAttribute('data-release-url')).filter(Boolean)"
        )
    except Exception:
        release_urls = []
    for release_url in release_urls:
        if isinstance(release_url, str):
            collect_url(
                collected,
                release_url,
                "playwright_data_release_url",
                referer=base_url,
            )

    html = await page.content()
    for candidate in extract_candidates(html, base_url):
        collected.setdefault(candidate.url, candidate)


async def explore_download_controls(page, collected: dict[str, InstallerCandidate]) -> None:
    """Activa hasta tres niveles y cuatro controles por nivel, reextrayendo tras cada clic hasta
    hallar un artefacto.

    Args:
        page: Página Playwright cuyo DOM se analiza.
        collected: Mapa mutable de candidatos indexados por URL.
    """
    seen_controls: set[str] = set()
    for _depth in range(3):
        locator = page.locator("a, button, [role=button]").filter(has_text=DOWNLOAD_CONTROL_PATTERN)
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
        if clicked == 0 or any(candidate.extension for candidate in collected.values()):
            break
