from __future__ import annotations

import re

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
                    lambda request: collect_url(
                        collected,
                        request.url,
                        "playwright_request",
                        referer=url,
                    ),
                )
                page.on(
                    "response",
                    lambda response: collect_url(
                        collected,
                        response.url,
                        "playwright_response",
                        referer=url,
                    ),
                )

                try:
                    await page.goto(
                        url,
                        wait_until="domcontentloaded",
                        timeout=self.settings.playwright_timeout_ms,
                    )
                    await collect_page_candidates(collected, page, url)

                    locator = page.locator("a, button, [role=button]").filter(
                        has_text=re.compile(r"download|descargar|installer|instalador|setup", re.I)
                    )
                    count = await locator.count()
                    for index in range(min(count, 4)):
                        try:
                            await locator.nth(index).click(timeout=1200, no_wait_after=True)
                            await page.wait_for_timeout(400)
                            await collect_page_candidates(collected, page, page.url)
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
    *,
    referer: str | None = None,
) -> None:
    if URL_PATTERN.search(url) or re.search(r"/(?:download|installer|setup)(?:[/?]|$)", url, re.I):
        collected.setdefault(url, InstallerCandidate(url=url, source=source, referer=referer))


async def collect_page_candidates(collected, page, base_url: str) -> None:
    html = await page.content()
    for candidate in extract_candidates(html, base_url):
        collected.setdefault(candidate.url, candidate)
