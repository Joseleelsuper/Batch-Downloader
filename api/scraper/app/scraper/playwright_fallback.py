from __future__ import annotations

from app.core.config import Settings
from app.scraper.candidates import InstallerCandidate, URL_PATTERN, extract_candidates


class PlaywrightCandidateCollector:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    async def collect(self, url: str) -> list[InstallerCandidate]:
        try:
            from playwright.async_api import async_playwright
        except ImportError:
            return []

        collected: dict[str, InstallerCandidate] = {}
        try:
            async with async_playwright() as playwright:
                browser = await playwright.chromium.launch(headless=True)
                page = await browser.new_page()

                page.on(
                    "request",
                    lambda request: collect_url(collected, request.url, "playwright_request"),
                )
                page.on(
                    "response",
                    lambda response: collect_url(collected, response.url, "playwright_response"),
                )

                try:
                    await page.goto(
                        url,
                        wait_until="networkidle",
                        timeout=self.settings.playwright_timeout_ms,
                    )
                    html = await page.content()
                    for candidate in extract_candidates(html, url):
                        collected.setdefault(candidate.url, candidate)

                    for label in ("download", "descargar", "installer", "instalador", "setup"):
                        locator = page.get_by_text(label, exact=False)
                        count = await locator.count()
                        for index in range(min(count, 3)):
                            try:
                                await locator.nth(index).click(timeout=1500)
                                await page.wait_for_load_state("networkidle", timeout=3000)
                            except Exception:
                                continue
                except Exception:
                    pass
                finally:
                    await browser.close()
        except Exception:
            return []

        return list(collected.values())


def collect_url(
    collected: dict[str, InstallerCandidate],
    url: str,
    source: str,
) -> None:
    if URL_PATTERN.search(url):
        collected.setdefault(url, InstallerCandidate(url=url, source=source))
