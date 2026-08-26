"""Implementa las responsabilidades del módulo `candidates`.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import PurePosixPath
from urllib.parse import parse_qs, unquote, urljoin, urlparse, urlunparse

from selectolax.parser import HTMLParser

from app.scraper.artifacts import (
    DEFAULT_ARTIFACT_FORMAT_REGISTRY,
    ArtifactArchitecture,
    ArtifactPlatform,
)
from app.scraper.text import normalize_text

PREFERRED_EXTENSIONS = DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions
"""Constante que define `PREFERRED_EXTENSIONS`.
"""
WINDOWS_INSTALLER_EXTENSIONS = DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions_for(
    ArtifactPlatform.WINDOWS
)
"""Constante que define `WINDOWS_INSTALLER_EXTENSIONS`.
"""
MACOS_INSTALLER_EXTENSIONS = DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions_for(
    ArtifactPlatform.MACOS
)
"""Constante que define `MACOS_INSTALLER_EXTENSIONS`.
"""
LINUX_INSTALLER_EXTENSIONS = DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions_for(
    ArtifactPlatform.LINUX
)
"""Constante que define `LINUX_INSTALLER_EXTENSIONS`.
"""
UNSUPPORTED_DOWNLOAD_EXTENSIONS = (
    ".apk",
    ".asc",
    ".bib",
    ".checksum",
    ".css",
    ".html",
    ".json",
    ".pdf",
    ".sig",
    ".sha1",
    ".sha256",
    ".sha512",
    ".txt",
    ".xml",
    ".yml",
    ".yaml",
)
"""Constante que define `UNSUPPORTED_DOWNLOAD_EXTENSIONS`.
"""

POSITIVE_KEYWORDS = (
    "download",
    "descargar",
    "installer",
    "instalador",
    "setup",
    "install",
    "windows",
    "win64",
    "x64",
    "offline",
    "standalone",
)
"""Constante que define `POSITIVE_KEYWORDS`.
"""

NEGATIVE_KEYWORDS = (
    "documentation",
    "docs",
    "release-notes",
    "source",
    "checksum",
    "signature",
    "torrent",
    "beta",
    "portable",
    "uninstall",
    "opengl",
    "noselfupdate",
)
"""Constante que define `NEGATIVE_KEYWORDS`.
"""

URL_PATTERN = re.compile(
    r"https?://[^\s'\"<>\\]+|(?:(?:\.\./|\.\/|/)?[A-Za-z0-9._~!$&'()*+,;=:@%/-]+"
    r"(?:\.exe|\.msi|\.msix|\.appx|\.zip|\.deb|\.rpm|\.appimage|\.dmg|\.pkg|\.tar\.gz|\.jar)(?:\?[^\s'\"<>\\]*)?)",
    re.IGNORECASE,
)
"""Constante que define `URL_PATTERN`.
"""

# Los scripts suelen contener fragmentos JavaScript arbitrarios terminados en ".exe"
# o ".deb". Solo las URL absolutas son fiables para extraerlas del cuerpo de un script;
# los atributos normales de enlaces y formularios se procesan por separado más abajo.
ABSOLUTE_URL_PATTERN = re.compile(r"https?://[^\s'\"<>\\]+", re.IGNORECASE)
"""Constante que define `ABSOLUTE_URL_PATTERN`.
"""

# Los controles dinámicos de descarga suelen guardar la ruta siguiente en JavaScript
# en línea, por ejemplo: onclick="location.href='/download/launcherPC/'". Aquí solo se
# aceptan valores entre comillas con forma de navegación para no tratar JavaScript
# arbitrario como una URL.
EMBEDDED_NAVIGATION_URL_PATTERN = re.compile(
    r"(['\"])((?:https?:)?//[^'\"]+|/(?!/)[^'\"]+|\.\.?/[^'\"]+)\1",
    re.IGNORECASE,
)
"""Constante que define `EMBEDDED_NAVIGATION_URL_PATTERN`.
"""

VERSION_PATTERN = re.compile(r"(?<!\d)v?(\d+(?:\.\d+){1,4})", re.I)
"""Constante que define `VERSION_PATTERN`.
"""


@dataclass(frozen=True)
class InstallerCandidate:
    """Representa el componente `InstallerCandidate`.
    """
    url: str
    """Atributo de clase `url` de `InstallerCandidate`.
    """
    source: str
    """Atributo de clase `source` de `InstallerCandidate`.
    """
    label: str | None = None
    """Atributo de clase `label` de `InstallerCandidate`.
    """
    context: str | None = None
    """Atributo de clase `context` de `InstallerCandidate`.
    """
    score: int = 0
    """Atributo de clase `score` de `InstallerCandidate`.
    """
    asset_kind: str | None = None
    """Atributo de clase `asset_kind` de `InstallerCandidate`.
    """
    match_tokens: tuple[str, ...] = ()
    """Atributo de clase `match_tokens` de `InstallerCandidate`.
    """
    referer: str | None = None
    """Atributo de clase `referer` de `InstallerCandidate`.
    """

    @property
    def extension(self) -> str | None:
        """Ejecuta `extension` dentro de `InstallerCandidate`.

        Returns:
            str | None: Resultado producido por la operación.
        """
        return detect_extension(self.url)


def extract_candidates(html: str, base_url: str) -> list[InstallerCandidate]:
    """Ejecuta la operación `extract_candidates`.

    Args:
        html (str): Valor de `html` utilizado por la operación.
        base_url (str): Dirección de `base` que debe procesarse.

    Returns:
        list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
    """
    parser = HTMLParser(html)
    candidates: list[InstallerCandidate] = []

    for node in parser.css("a, area"):
        href = node.attributes.get("href")
        if href:
            candidates.append(
                InstallerCandidate(
                    url=safe_urljoin(base_url, href) or "",
                    source="href",
                    label=node.text(separator=" ", strip=True),
                    context=(node.html or "")[:500],
                    referer=base_url,
                )
            )

    for node in parser.css("form"):
        action = node.attributes.get("action")
        if action:
            candidates.append(
                InstallerCandidate(
                    url=safe_urljoin(base_url, action) or "",
                    source="form",
                    label=node.attributes.get("aria-label") or node.text(separator=" ", strip=True),
                    context=(node.html or "")[:500],
                    referer=base_url,
                )
            )

    for node in parser.css("button, [role=button]"):
        values = " ".join(
            value for value in node.attributes.values() if isinstance(value, str)
        )
        text = f"{node.text(separator=' ', strip=True)} {values}"
        for match in URL_PATTERN.findall(text):
            candidates.append(
                InstallerCandidate(
                    url=safe_urljoin(base_url, match) or "",
                    source="button",
                    label=node.text(separator=" ", strip=True),
                    context=(node.html or "")[:500],
                    referer=base_url,
                )
            )

    for node in parser.css(
        "[onclick], [data-url], [data-href], [data-link], "
        "[data-download], [data-download-url]"
    ):
        label = node.text(separator=" ", strip=True)
        for attribute, value in node.attributes.items():
            if not isinstance(value, str):
                continue
            for embedded_url in navigation_urls_from_attribute(value):
                candidates.append(
                    InstallerCandidate(
                        url=safe_urljoin(base_url, embedded_url) or "",
                        source=f"attribute:{attribute}",
                        label=label,
                        context=(node.html or "")[:500],
                        referer=base_url,
                    )
                )

    for node in parser.css("script"):
        for match in ABSOLUTE_URL_PATTERN.findall(node.text() or ""):
            candidates.append(
                InstallerCandidate(
                    url=match,
                    source="script",
                    context=match,
                    referer=base_url,
                )
            )

    for node in parser.css("meta"):
        content = node.attributes.get("content", "")
        for match in ABSOLUTE_URL_PATTERN.findall(content or ""):
            candidates.append(
                InstallerCandidate(
                    url=match,
                    source="meta",
                    context=match,
                    referer=base_url,
                )
            )

    deduped: dict[str, InstallerCandidate] = {}
    for candidate in candidates:
        normalized_url = candidate.url.strip()
        try:
            scheme = urlparse(normalized_url).scheme
        except ValueError:
            continue
        if scheme not in {"http", "https"}:
            continue
        if normalized_url and normalized_url not in deduped:
            deduped[normalized_url] = candidate
    return list(deduped.values())


def safe_urljoin(base_url: str, value: str) -> str | None:
    """Ejecuta la operación `safe_urljoin`.

    Args:
        base_url (str): Dirección de `base` que debe procesarse.
        value (str): Valor que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    try:
        return urljoin(base_url, value)
    except ValueError:
        return None


def navigation_urls_from_attribute(value: str) -> list[str]:
    """Ejecuta la operación `navigation_urls_from_attribute`.

    Args:
        value (str): Valor que debe procesarse.

    Returns:
        list[str]: Colección de elementos obtenidos por la operación.
    """
    stripped = value.strip()
    urls: list[str] = []
    if stripped.startswith(("http://", "https://", "//", "/", "./", "../")):
        urls.append(stripped)
    urls.extend(match.group(2) for match in EMBEDDED_NAVIGATION_URL_PATTERN.finditer(value))
    return list(dict.fromkeys(urls))


def score_candidate(
    candidate: InstallerCandidate,
    app_name: str | None = None,
    package_id: str | None = None,
    publisher: str | None = None,
    version: str | None = None,
) -> InstallerCandidate:
    """Ejecuta la operación `score_candidate`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.
        app_name (str | None): Valor de `app_name` utilizado por la operación.
        package_id (str | None): Identificador de `package` utilizado por la operación.
        publisher (str | None): Valor de `publisher` utilizado por la operación.
        version (str | None): Valor de `version` utilizado por la operación.

    Returns:
        InstallerCandidate: Resultado producido por la operación.
    """
    text = normalize_text(f"{candidate.url} {candidate.label or ''} {candidate.context or ''}")
    score = 0
    extension = detect_extension(candidate.url)
    asset_kind = (
        "source_archive"
        if is_github_source_archive(candidate.url)
        else candidate.asset_kind or classify_asset(candidate.url)
    )
    if asset_kind == "source_archive":
        score -= 150
    if extension in (
        WINDOWS_INSTALLER_EXTENSIONS
        + MACOS_INSTALLER_EXTENSIONS
        + (".deb", ".rpm", ".appimage")
    ):
        score += 70
    elif extension == ".zip":
        score += 25
    elif extension in PREFERRED_EXTENSIONS:
        score += 50
    if extension == ".zip" and registered_domain(candidate.url) == "github.com":
        trusted_binary_blob = (
            candidate.asset_kind == "winstall_download"
            and is_github_raw_file(candidate.url)
        )
        score += 10 if is_github_release_asset(candidate.url) or trusted_binary_blob else -90
    if any(
        keyword_present(text, keyword)
        for keyword in (
            "x64",
            "x86_64",
            "amd64",
            "64-bit",
            "64bit",
            "x86",
            "i386",
            "i686",
            "arm64",
            "aarch64",
            "apple silicon",
        )
    ):
        score += 15
    for keyword in POSITIVE_KEYWORDS:
        if keyword_present(text, keyword):
            score += 8 if keyword not in {"download", "descargar"} else 20
    for keyword in NEGATIVE_KEYWORDS:
        if keyword_present(text, keyword):
            if keyword == "portable" and candidate.asset_kind == "winstall_download":
                continue
            score -= 50
    match_tokens = app_match_tokens(
        text=text,
        app_name=app_name,
        package_id=package_id,
        publisher=publisher,
        version=version,
    )
    score += len(match_tokens) * 12
    score += variant_score(text=text, app_name=app_name, package_id=package_id)
    return InstallerCandidate(
        url=candidate.url,
        source=candidate.source,
        label=candidate.label,
        context=candidate.context,
        score=score,
        asset_kind=asset_kind,
        match_tokens=tuple(match_tokens),
        referer=candidate.referer,
    )


def classify_asset(url: str) -> str:
    """Ejecuta la operación `classify_asset`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    if is_github_source_archive(url):
        return "source_archive"
    if is_github_release_asset(url) and detect_extension(url) == ".zip":
        return "release_zip"
    if detect_extension(url) in WINDOWS_INSTALLER_EXTENSIONS:
        return "installer"
    if detect_extension(url) in MACOS_INSTALLER_EXTENSIONS:
        return "installer"
    if detect_extension(url) in LINUX_INSTALLER_EXTENSIONS:
        return "installer"
    if detect_extension(url) in PREFERRED_EXTENSIONS:
        return "archive"
    return "unknown"


def is_github_source_archive(url: str) -> bool:
    """Indica si se cumple la operación `github_source_archive`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    parsed = urlparse(url)
    host = parsed.netloc.lower()
    path = parsed.path.lower()
    if host == "codeload.github.com":
        return True
    if host.endswith("github.com") and any(
        marker in path for marker in ("/archive/", "/zipball/", "/tarball/")
    ):
        return True
    filename = PurePosixPath(path).name
    return host.endswith("github.com") and filename in {"main.zip", "master.zip"}


def is_github_release_asset(url: str) -> bool:
    """Indica si se cumple la operación `github_release_asset`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    parsed = urlparse(url)
    return (
        parsed.netloc.lower().endswith("github.com")
        and "/releases/download/" in parsed.path.lower()
    )


def is_github_raw_file(url: str) -> bool:
    """Distingue un blob explícito de los ZIP de código generados por GitHub."""
    parsed = urlparse(url)
    host = parsed.netloc.lower()
    path = parsed.path.lower()
    return host == "raw.githubusercontent.com" or (
        host.endswith("github.com") and "/raw/" in path
    )


def app_match_tokens(
    text: str,
    app_name: str | None,
    package_id: str | None,
    publisher: str | None,
    version: str | None,
) -> list[str]:
    """Ejecuta la operación `app_match_tokens`.

    Args:
        text (str): Valor de `text` utilizado por la operación.
        app_name (str | None): Valor de `app_name` utilizado por la operación.
        package_id (str | None): Identificador de `package` utilizado por la operación.
        publisher (str | None): Valor de `publisher` utilizado por la operación.
        version (str | None): Valor de `version` utilizado por la operación.

    Returns:
        list[str]: Colección de elementos obtenidos por la operación.
    """
    raw = " ".join(value for value in (app_name, package_id, publisher, version) if value)
    tokens = product_tokens(raw)
    return [token for token in tokens if token in text]


def product_tokens(value: str) -> list[str]:
    """Ejecuta la operación `product_tokens`.

    Args:
        value (str): Valor que debe procesarse.

    Returns:
        list[str]: Colección de elementos obtenidos por la operación.
    """
    normalized = normalize_text(value.replace(".", " ").replace("_", " ").replace("-", " "))
    stopwords = {
        "app",
        "application",
        "desktop",
        "for",
        "inc",
        "installer",
        "launcher",
        "llc",
        "software",
        "windows",
    }
    tokens = [
        token
        for token in re.findall(r"[a-z0-9]+", normalized)
        if len(token) >= 3 and token not in stopwords
    ]
    return list(dict.fromkeys(tokens))


def variant_score(text: str, app_name: str | None, package_id: str | None) -> int:
    """Ejecuta la operación `variant_score`.

    Args:
        text (str): Valor de `text` utilizado por la operación.
        app_name (str | None): Valor de `app_name` utilizado por la operación.
        package_id (str | None): Identificador de `package` utilizado por la operación.

    Returns:
        int: Resultado producido por la operación.
    """
    app_text = normalize_text(f"{app_name or ''} {package_id or ''}")
    score = 0
    variants = {
        "graphing": ("graphing",),
        "geometry": ("geometry",),
        "cas": ("cas",),
        "suite": ("suite", "win-suite", "calculator-suite"),
        "classic": ("classic",),
    }
    for variant, aliases in variants.items():
        app_wants_variant = variant in app_text or (
            variant == "suite" and "calculator suite" in app_text
        )
        candidate_has_variant = any(alias in text for alias in aliases)
        if app_wants_variant and candidate_has_variant:
            score += 35
        elif not app_wants_variant and candidate_has_variant:
            score -= 25
    return score


def detect_extension(url: str) -> str | None:
    """Ejecuta la operación `detect_extension`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    return DEFAULT_ARTIFACT_FORMAT_REGISTRY.detect_extension(url)


def filename_from_url(url: str) -> str | None:
    """Ejecuta la operación `filename_from_url`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    parsed = urlparse(url)
    path = unquote(parsed.path)
    for name in [PurePosixPath(path).name, *reversed(path.split("/"))]:
        if name and "." in name and detect_extension(f"https://local.invalid/{name}"):
            return name[:255]
    query = parse_qs(parsed.query)
    for key in ("filename", "file", "download", "installer"):
        for value in query.get(key, []):
            name = PurePosixPath(unquote(value)).name
            if name and "." in name:
                return name[:255]
    return None


def candidate_text(candidate: InstallerCandidate) -> str:
    """Ejecuta la operación `candidate_text`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    return normalize_text(f"{candidate.url} {candidate.label or ''} {candidate.context or ''}")


def keyword_present(text: str, keyword: str) -> bool:
    """Ejecuta la operación `keyword_present`.

    Args:
        text (str): Valor de `text` utilizado por la operación.
        keyword (str): Valor de `keyword` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    normalized = normalize_text(keyword)
    pattern = re.escape(normalized).replace(r"\ ", r"\s+")
    return re.search(rf"(?<![a-z0-9]){pattern}(?![a-z0-9])", text) is not None


def candidate_has_download_intent(candidate: InstallerCandidate) -> bool:
    """Ejecuta la operación `candidate_has_download_intent`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    text = candidate_text(candidate)
    if candidate.asset_kind == "winstall_download":
        return True
    return any(
        keyword_present(text, keyword)
        for keyword in ("download", "descargar", "installer", "instalador", "setup", "install")
    )


def is_download_candidate(candidate: InstallerCandidate) -> bool:
    """Indica si se cumple la operación `download_candidate`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return bool(detect_extension(candidate.url)) or candidate_has_download_intent(candidate)


def candidate_variants(candidate: InstallerCandidate) -> list[InstallerCandidate]:
    """Ejecuta la operación `candidate_variants`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        list[InstallerCandidate]: Colección de elementos obtenidos por la operación.
    """
    variants = [candidate]
    for variant_factory in (
        https_upgrade_variant,
        elcomsoft_download_variant,
        s3_path_style_variant,
        sourceforge_mirror_variant,
    ):
        variant = variant_factory(candidate)
        if variant and variant.url not in {item.url for item in variants}:
            variants.append(variant)
    return variants


def https_upgrade_variant(candidate: InstallerCandidate) -> InstallerCandidate | None:
    """Prueba el mismo artefacto por TLS sin relajar la prohibición de HTTP."""
    parsed = urlparse(candidate.url)
    if parsed.scheme.lower() != "http" or not parsed.hostname:
        return None
    return InstallerCandidate(
        url=urlunparse(parsed._replace(scheme="https")),
        source=f"{candidate.source}_https_upgrade",
        label=candidate.label,
        context=candidate.context,
        asset_kind=candidate.asset_kind,
        referer=candidate.referer,
    )


def elcomsoft_download_variant(candidate: InstallerCandidate) -> InstallerCandidate | None:
    """Evita el mirror regional con TLS roto usando el CDN oficial canónico."""
    parsed = urlparse(candidate.url)
    host = (parsed.hostname or "").lower()
    if host not in {"elcomsoft.com", "www.elcomsoft.com", "us.elcomsoft.com"}:
        return None
    if not parsed.path.lower().startswith("/download/") or not candidate.extension:
        return None
    artifact_path = parsed.path.removeprefix("/download/").lstrip("/")
    if not artifact_path:
        return None
    return InstallerCandidate(
        url=urlunparse(
            (
                "https",
                "download.elcomsoft.com",
                f"/{artifact_path}",
                "",
                parsed.query,
                "",
            )
        ),
        source=f"{candidate.source}_elcomsoft_canonical",
        label=candidate.label,
        context=candidate.context,
        asset_kind=candidate.asset_kind,
        referer=candidate.referer,
    )


def s3_path_style_variant(candidate: InstallerCandidate) -> InstallerCandidate | None:
    """Ejecuta la operación `s3_path_style_variant`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        InstallerCandidate | None: Resultado producido por la operación.
    """
    parsed = urlparse(candidate.url)
    host = (parsed.hostname or "").lower()
    suffix = ".s3.amazonaws.com"
    if not host.endswith(suffix):
        return None
    bucket = host.removesuffix(suffix)
    if not bucket or "_" not in bucket:
        return None
    url = urlunparse(
        (
            "https",
            "s3.amazonaws.com",
            f"/{bucket}{parsed.path}",
            "",
            parsed.query,
            "",
        )
    )
    return InstallerCandidate(
        url=url,
        source=f"{candidate.source}_s3_path_style",
        label=candidate.label,
        context=candidate.context,
        asset_kind=candidate.asset_kind,
        referer=candidate.referer,
    )


def sourceforge_mirror_variant(candidate: InstallerCandidate) -> InstallerCandidate | None:
    """Ejecuta la operación `sourceforge_mirror_variant`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        InstallerCandidate | None: Resultado producido por la operación.
    """
    parsed = urlparse(candidate.url)
    host = (parsed.hostname or "").lower()
    if host in {"sourceforge.net", "www.sourceforge.net"}:
        match = re.fullmatch(
            r"/projects?/([^/]+)/files/(.+)/download/?",
            parsed.path,
            flags=re.IGNORECASE,
        )
        if not match:
            return None
        project, artifact_path = match.groups()
        routed_url = urlunparse(
            (
                "https",
                "downloads.sourceforge.net",
                f"/project/{project}/{artifact_path}",
                "",
                "",
                "",
            )
        )
    elif host.endswith(".dl.sourceforge.net"):
        routed_url = urlunparse(parsed._replace(netloc="downloads.sourceforge.net"))
    else:
        return None
    return InstallerCandidate(
        url=routed_url,
        source=f"{candidate.source}_sourceforge_router",
        label=candidate.label,
        context=candidate.context,
        asset_kind=candidate.asset_kind,
        referer=candidate.referer,
    )


def infer_operating_system(candidate: InstallerCandidate) -> str | None:
    """Ejecuta la operación `infer_operating_system`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    extension = candidate.extension
    text = candidate_text(candidate)
    if extension == ".tar.gz":
        if any(token in text for token in ("macos", "mac os", "darwin", "apple silicon")):
            return "macos"
        if any(token in text for token in ("linux", "ubuntu", "debian", "fedora")):
            return "linux"
        return "linux"
    operating_system = operating_system_for_extension(extension)
    if operating_system:
        return operating_system
    if any(token in text for token in ("windows", "win64", "win32", "x64.exe")):
        return "windows"
    if any(token in text for token in ("macos", "mac os", "darwin", "dmg", "apple silicon")):
        return "macos"
    if any(token in text for token in ("linux", "ubuntu", "debian", "fedora", "appimage")):
        return "linux"
    return None


def operating_system_for_extension(extension: str | None) -> str | None:
    """Ejecuta la operación `operating_system_for_extension`.

    Args:
        extension (str | None): Valor de `extension` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    platform = DEFAULT_ARTIFACT_FORMAT_REGISTRY.platform_for(extension)
    return platform.value if platform else None


def infer_architecture(candidate: InstallerCandidate) -> str:
    """Ejecuta la operación `infer_architecture`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        str: Resultado producido por la operación.
    """
    text = candidate_text(candidate)
    return DEFAULT_ARTIFACT_FORMAT_REGISTRY.infer_architecture(
        text,
        default=ArtifactArchitecture.X86_64,
    ).value


def has_architecture_token(text: str, token: str) -> bool:
    """Indica si existe la operación `architecture_token`.

    Args:
        text (str): Valor de `text` utilizado por la operación.
        token (str): Token utilizado para autorizar o correlacionar la operación.

    Returns:
        bool: Indica si se cumple la condición evaluada.
    """
    return re.search(rf"(?<![a-z0-9]){re.escape(token)}(?![a-z0-9])", text) is not None


def extract_version(candidate: InstallerCandidate) -> str | None:
    """Ejecuta la operación `extract_version`.

    Args:
        candidate (InstallerCandidate): Valor de `candidate` utilizado por la operación.

    Returns:
        str | None: Resultado producido por la operación.
    """
    try:
        parsed = urlparse(candidate.url)
        url_text = " ".join((parsed.path, parsed.query, parsed.fragment))
    except ValueError:
        url_text = candidate.url
    supporting_text = " ".join(
        strip_url_authorities(value)
        for value in (candidate.label, candidate.context)
        if value
    )
    raw = f"{url_text} {supporting_text}"
    matches = VERSION_PATTERN.findall(raw)
    if not matches:
        return None
    return matches[-1]


def strip_url_authorities(value: str) -> str:
    """Ejecuta la operación `strip_url_authorities`.

    Args:
        value (str): Valor que debe procesarse.

    Returns:
        str: Resultado producido por la operación.
    """
    return re.sub(r"https?://(?:\[[^\]]+\]|[^/\s'\"<>]+)", "", value, flags=re.I)


def registered_domain(url: str) -> str | None:
    """Ejecuta la operación `registered_domain`.

    Args:
        url (str): URL del recurso que debe procesarse.

    Returns:
        str | None: Resultado producido por la operación.
    """
    import tldextract

    extracted = tldextract.extract(url)
    if not extracted.domain or not extracted.suffix:
        return None
    return f"{extracted.domain}.{extracted.suffix}".lower()
