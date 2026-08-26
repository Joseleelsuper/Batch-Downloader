"""Implementa las responsabilidades del módulo `icon_resolver`.
"""
from __future__ import annotations

import asyncio
import base64
import ipaddress
import json
import re
import socket
from dataclasses import dataclass
from urllib.parse import quote, urljoin, urlparse

import httpx
from selectolax.parser import HTMLParser

from app.core.config import Settings
from app.scraper.github import parse_github_repo
from app.scraper.winstall import WinstallApp

BADGE_MARKERS = (
    "badge",
    "badgen.net",
    "build",
    "coverage",
    "license",
    "shields.io",
    "workflow",
)
"""Constante que define `BADGE_MARKERS`.
"""

# Esta expresión regular encuentra imágenes Markdown como ![alt](url "título").
IMAGE_MARKDOWN_PATTERN = re.compile(r"!\[([^\]]*)]\(([^)\s]+)(?:\s+\"[^\"]*\")?\)")
"""Constante que define `IMAGE_MARKDOWN_PATTERN`.
"""

# Esta expresión regular encuentra imágenes HTML como <img src="url" alt="texto">.
IMAGE_HTML_PATTERN = re.compile(r"<img[^>]+src=[\"']([^\"']+)[\"']", re.IGNORECASE)
"""Constante que define `IMAGE_HTML_PATTERN`.
"""


@dataclass(frozen=True)
class IconResult:
    """Representa el resultado de `Icon`.
    """
    url: str
    """Atributo de clase `url` de `IconResult`.
    """
    source: str
    """Atributo de clase `source` de `IconResult`.
    """


class IconResolver:
    """Representa el componente `IconResolver`.
    """
    def __init__(self, settings: Settings, client: httpx.AsyncClient | None = None) -> None:
        """Inicializa una instancia de `IconResolver`.

        Args:
            settings (Settings): Configuración del servicio.
            client (httpx.AsyncClient | None): Cliente utilizado para ejecutar el escenario.
        """
        self.settings = settings
        """Estado de instancia asociado a `settings`.
        """
        self.client = client
        """Estado de instancia asociado a `client`.
        """

    async def resolve(self, app: WinstallApp) -> IconResult | None:
        """Ejecuta `resolve` dentro de `IconResolver`.

        Args:
            app (WinstallApp): Aplicación sobre la que se realiza la operación.

        Returns:
            IconResult | None: Resultado producido por la operación.
        """
        homepage = app.homepage
        if not homepage:
            return None

        owns_client = self.client is None
        client = self.client or httpx.AsyncClient(
            timeout=self.settings.request_timeout_seconds,
            follow_redirects=False,
            headers={"User-Agent": "Mozilla/5.0 BatchDownloaderScraper/0.1"},
        )
        try:
            candidate: IconResult | None = None
            if parse_github_repo(homepage):
                candidate = await self._from_github(client, homepage)
            if candidate is None:
                candidate = await self._from_official_page(client, homepage)
            return await self._validate_image(client, candidate) if candidate else None
        finally:
            if owns_client:
                await client.aclose()

    async def _from_official_page(
        self,
        client: httpx.AsyncClient,
        homepage: str,
    ) -> IconResult | None:
        """Ejecuta el paso interno `_from_official_page`.

        Args:
            client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
            homepage (str): Valor de `homepage` utilizado por la operación.

        Returns:
            IconResult | None: Resultado producido por la operación.
        """
        try:
            response = await client.get(homepage)
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        content_type = response.headers.get("content-type", "")
        if content_type and "html" not in content_type:
            return None

        parser = HTMLParser(response.text)
        base_url = str(response.url)
        for selector, attr, source in (
            ("link[rel]", "href", "official_link_icon"),
            ("meta[property='og:image']", "content", "official_og_image"),
            ("meta[name='twitter:image']", "content", "official_twitter_image"),
        ):
            result = self._first_icon_from_nodes(parser, selector, attr, base_url, source)
            if result:
                return result

        manifest = self._first_icon_from_nodes(
            parser,
            "link[rel='manifest']",
            "href",
            base_url,
            "official_manifest",
            allow_rel_any=True,
        )
        if manifest:
            manifest_icon = await self._from_manifest(client, manifest.url)
            if manifest_icon:
                return manifest_icon

        return None

    def _first_icon_from_nodes(
        self,
        parser: HTMLParser,
        selector: str,
        attr: str,
        base_url: str,
        source: str,
        allow_rel_any: bool = False,
    ) -> IconResult | None:
        """Ejecuta el paso interno `_first_icon_from_nodes`.

        Args:
            parser (HTMLParser): Valor de `parser` utilizado por la operación.
            selector (str): Valor de `selector` utilizado por la operación.
            attr (str): Valor de `attr` utilizado por la operación.
            base_url (str): Dirección de `base` que debe procesarse.
            source (str): Fuente de descarga sobre la que se actúa.
            allow_rel_any (bool): Valor de `allow_rel_any` utilizado por la operación.

        Returns:
            IconResult | None: Resultado producido por la operación.
        """
        for node in parser.css(selector):
            if not allow_rel_any:
                rel = node.attributes.get("rel") or ""
                if node.tag == "link" and "icon" not in rel.lower():
                    continue
            value = node.attributes.get(attr)
            if not value:
                continue
            icon_url = urljoin(base_url, value)
            if usable_icon_url(icon_url):
                return IconResult(icon_url, source)
        return None

    async def _from_manifest(
        self,
        client: httpx.AsyncClient,
        manifest_url: str,
    ) -> IconResult | None:
        """Ejecuta el paso interno `_from_manifest`.

        Args:
            client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
            manifest_url (str): Dirección de `manifest` que debe procesarse.

        Returns:
            IconResult | None: Resultado producido por la operación.
        """
        try:
            response = await client.get(manifest_url)
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        try:
            payload = response.json()
        except json.JSONDecodeError:
            return None
        icons = payload.get("icons")
        if not isinstance(icons, list):
            return None
        best_url: str | None = None
        best_size = -1
        for icon in icons:
            if not isinstance(icon, dict):
                continue
            src = icon.get("src")
            if not isinstance(src, str):
                continue
            size = largest_icon_size(icon.get("sizes"))
            icon_url = urljoin(str(response.url), src)
            if usable_icon_url(icon_url) and size >= best_size:
                best_url = icon_url
                best_size = size
        return IconResult(best_url, "official_manifest_icon") if best_url else None

    async def _from_github(
        self,
        client: httpx.AsyncClient,
        homepage: str,
    ) -> IconResult | None:
        """Ejecuta el paso interno `_from_github`.

        Args:
            client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
            homepage (str): Valor de `homepage` utilizado por la operación.

        Returns:
            IconResult | None: Resultado producido por la operación.
        """
        repo = parse_github_repo(homepage)
        if not repo:
            return None

        readme = await self._github_readme_icon(client, repo.owner, repo.name)
        if readme:
            return readme

        page_image = await self._github_page_image(client, homepage)
        if page_image:
            return page_image

        avatar = self._github_avatar(repo.owner)
        if avatar:
            return avatar

        return None

    async def _github_page_image(
        self,
        client: httpx.AsyncClient,
        homepage: str,
    ) -> IconResult | None:
        """Ejecuta el paso interno `_github_page_image`.

        Args:
            client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
            homepage (str): Valor de `homepage` utilizado por la operación.

        Returns:
            IconResult | None: Resultado producido por la operación.
        """
        try:
            response = await client.get(homepage)
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        parser = HTMLParser(response.text)
        for selector, attr in (
            ("meta[property='og:image']", "content"),
            ("meta[name='twitter:image']", "content"),
        ):
            result = self._first_icon_from_nodes(
                parser,
                selector,
                attr,
                str(response.url),
                "github_page_image",
                allow_rel_any=True,
            )
            if result and is_github_social_image(result.url):
                return result
        return None

    def _github_avatar(self, owner: str) -> IconResult:
        """Ejecuta el paso interno `_github_avatar`.

        Args:
            owner (str): Valor de `owner` utilizado por la operación.

        Returns:
            IconResult: Resultado producido por la operación.
        """
        return IconResult(
            f"https://github.com/{quote(owner, safe='')}.png?size=128",
            "github_owner_avatar",
        )

    async def _github_readme_icon(
        self,
        client: httpx.AsyncClient,
        owner: str,
        repo: str,
    ) -> IconResult | None:
        """Ejecuta el paso interno `_github_readme_icon`.

        Args:
            client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
            owner (str): Valor de `owner` utilizado por la operación.
            repo (str): Valor de `repo` utilizado por la operación.

        Returns:
            IconResult | None: Resultado producido por la operación.
        """
        try:
            response = await client.get(
                f"https://api.github.com/repos/{owner}/{repo}/readme",
                headers={"Accept": "application/vnd.github.raw"},
            )
        except httpx.HTTPError:
            return None
        if not response.is_success:
            return None
        text = readme_text(response)
        for label, image_url in readme_images(text):
            if is_badge_image(label, image_url):
                continue
            resolved = resolve_github_readme_image(owner, repo, image_url)
            if usable_icon_url(resolved):
                return IconResult(resolved, "github_readme_image")
        return None

    async def _validate_image(
        self,
        client: httpx.AsyncClient,
        result: IconResult,
    ) -> IconResult | None:
        """Ejecuta el paso interno `_validate_image`.

        Args:
            client (httpx.AsyncClient): Cliente utilizado para ejecutar el escenario.
            result (IconResult): Resultado que debe procesarse.

        Returns:
            IconResult | None: Resultado producido por la operación.
        """
        candidate = result.url
        for _ in range(self.settings.max_redirects + 1):
            if not await public_https_url(candidate):
                return None
            request = client.build_request("GET", candidate)
            try:
                response = await client.send(request, stream=True, follow_redirects=False)
            except httpx.HTTPError:
                return None
            try:
                if response.is_redirect:
                    location = response.headers.get("location")
                    if not location:
                        return None
                    candidate = urljoin(str(response.url), location)
                    continue
                if not response.is_success or not await public_https_url(str(response.url)):
                    return None
                content_type = response.headers.get("content-type", "").lower()
                if not content_type.startswith("image/"):
                    return None
                content_length = int_or_none(response.headers.get("content-length", ""))
                if content_length is not None and content_length > self.settings.icon_max_bytes:
                    return None
                bytes_read = 0
                async for chunk in response.aiter_bytes():
                    bytes_read += len(chunk)
                    if bytes_read > self.settings.icon_max_bytes:
                        return None
                return IconResult(str(response.url), result.source)
            finally:
                await response.aclose()
        return None


def largest_icon_size(value: object) -> int:
    """Ejecuta la operación `largest_icon_size`.

    Args:
        value (object): Valor que debe procesarse.

    Returns:
        int: Resultado producido por la operación.
    """
    if not isinstance(value, str):
        return 0
    sizes = [int(match) for match in re.findall(r"(\d+)x\d+", value)]
    return max(sizes) if sizes else 0


def readme_text(response: httpx.Response) -> str:
    """Ejecuta la operación `readme_text`.

    Args:
        response (httpx.Response): Respuesta que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    content_type = response.headers.get("content-type", "")
    if "json" not in content_type:
        return response.text
    payload = response.json()
    content = payload.get("content")
    if isinstance(content, str):
        try:
            return base64.b64decode(content).decode("utf-8", errors="ignore")
        except ValueError:
            return ""
    return ""


def readme_images(text: str) -> list[tuple[str, str]]:
    """Ejecuta la operación `readme_images`.

    Args:
        text (str): Valor de `text` utilizado por la operación.

    Returns:
        list[tuple[str, str]]: Colección de elementos obtenidos por la operación.
    """
    images: list[tuple[str, str]] = []
    for match in IMAGE_MARKDOWN_PATTERN.finditer(text):
        images.append((match.group(1), match.group(2)))
    for match in IMAGE_HTML_PATTERN.finditer(text):
        images.append(("", match.group(1)))
    return images


def resolve_github_readme_image(owner: str, repo: str, image_url: str) -> str:
    """Resuelve la operación `github_readme_image`.

    Args:
        owner (str): Valor de `owner` utilizado por la operación.
        repo (str): Valor de `repo` utilizado por la operación.
        image_url (str): Dirección de `image` que debe procesarse.

    Returns:
        str: Resultado de `resolve_github_readme_image`.
    """
    parsed = urlparse(image_url)
    if parsed.scheme in {"http", "https"}:
        return image_url
    return f"https://raw.githubusercontent.com/{owner}/{repo}/HEAD/{image_url.lstrip('./')}"


def usable_icon_url(url: str) -> bool:
    """Ejecuta la operación `usable_icon_url`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    parsed = urlparse(url)
    return parsed.scheme == "https" and bool(parsed.hostname) and not is_badge_image("", url)


def is_github_social_image(url: str) -> bool:
    """Indica si se cumple la operación `github_social_image`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    hostname = (urlparse(url).hostname or "").lower()
    return hostname == "repository-images.githubusercontent.com" or hostname.endswith(
        ".repository-images.githubusercontent.com"
    )


async def public_https_url(url: str) -> bool:
    """Ejecuta la operación `public_https_url`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.port not in {None, 443}:
        return False
    try:
        addresses = await asyncio.get_running_loop().getaddrinfo(
            parsed.hostname,
            443,
            type=socket.SOCK_STREAM,
        )
    except OSError:
        return False
    if not addresses:
        return False
    return all(ipaddress.ip_address(item[4][0]).is_global for item in addresses)


def int_or_none(value: str) -> int | None:
    """Ejecuta la operación `int_or_none`.

    Args:
        value (str): Valor que debe procesarse.

    Returns:
        int | None: Resultado producido por la operación.
    """
    try:
        return int(value)
    except ValueError:
        return None


def is_badge_image(label: str, url: str) -> bool:
    """Indica si se cumple la operación `badge_image`.

    Args:
        label (str): Valor de `label` utilizado por la operación.
        url (str): URL del recurso que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    text = f"{label} {url}".lower()
    return any(marker in text for marker in BADGE_MARKERS)
