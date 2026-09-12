"""Extrae posibles instaladores de HTML, puntúa su coincidencia con la aplicación y deriva
variantes y contexto de plataforma.
La extracción y puntuación no validan por sí solas la seguridad ni el contenido del destino.

See Also:
    app.scraper.validator.DownloadValidator: Comprueba red, formato y confianza antes de
        publicar.
    app.scraper.installer_policy: Ordena y filtra los candidatos para el catálogo.
"""

from __future__ import annotations

import re
from collections.abc import Iterator
from dataclasses import dataclass, replace
from itertools import chain
from pathlib import PurePosixPath
from urllib.parse import parse_qs, unquote, urljoin, urlparse, urlunparse

from selectolax.parser import HTMLParser, Node

from app.scraper.artifacts import (
    DEFAULT_ARTIFACT_FORMAT_REGISTRY,
    ArtifactArchitecture,
    ArtifactPlatform,
)
from app.scraper.text import normalize_text

PREFERRED_EXTENSIONS = DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions

WINDOWS_INSTALLER_EXTENSIONS = DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions_for(
    ArtifactPlatform.WINDOWS
)

MACOS_INSTALLER_EXTENSIONS = DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions_for(ArtifactPlatform.MACOS)

LINUX_INSTALLER_EXTENSIONS = DEFAULT_ARTIFACT_FORMAT_REGISTRY.extensions_for(ArtifactPlatform.LINUX)

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


URL_PATTERN = re.compile(
    r"https?://[^\s'\"<>\\]+|(?:(?:\.\./|\.\/|/)?[A-Za-z0-9._~!$&'()*+,;=:@%/-]+"
    r"(?:\.exe|\.msi|\.msix|\.appx|\.zip|\.deb|\.rpm|\.appimage|\.dmg|\.pkg\.tar\.zst|\.pkg|\.tar\.gz|\.jar)(?:\?[^\s'\"<>\\]*)?)",
    re.IGNORECASE,
)


# Los scripts suelen contener fragmentos JavaScript arbitrarios terminados en ".exe"
# o ".deb". Solo las URL absolutas son fiables para extraerlas del cuerpo de un script;
# los atributos normales de enlaces y formularios se procesan por separado más abajo.
ABSOLUTE_URL_PATTERN = re.compile(r"https?://[^\s'\"<>\\]+", re.IGNORECASE)


# Los controles dinámicos de descarga suelen guardar la ruta siguiente en JavaScript
# en línea, por ejemplo: onclick="location.href='/download/launcherPC/'". Aquí solo se
# aceptan valores entre comillas con forma de navegación para no tratar JavaScript
# arbitrario como una URL.
EMBEDDED_NAVIGATION_URL_PATTERN = re.compile(
    r"(['\"])((?:https?:)?//[^'\"]+|/(?!/)[^'\"]+|\.\.?/[^'\"]+)\1",
    re.IGNORECASE,
)


VERSION_PATTERN = re.compile(r"(?<!\d)v?(\d+(?:\.\d+){1,4})", re.I)



@dataclass(frozen=True)
class InstallerCandidate:
    """Conserva la URL y las evidencias que permiten priorizar y validar un posible instalador
    sin modificar la entrada original.

    Attributes:
        url, source: Destino propuesto y procedencia de extracción.
        label, context: Texto visible y contexto de página o publicación.
        score, asset_kind, match_tokens: Puntuación, clase de recurso y términos de aplicación
            coincidentes.
        referer: Página de origen para solicitudes que requieren contexto de navegación.
    """

    url: str

    source: str

    label: str | None = None

    context: str | None = None

    score: int = 0

    asset_kind: str | None = None

    match_tokens: tuple[str, ...] = ()

    referer: str | None = None


    @property
    def extension(self) -> str | None:
        """Detecta un sufijo de instalador en la URL mediante el registro compartido de formatos.

        Returns:
            extensión conocida o None.
        """
        return detect_extension(self.url)


def _node_candidate(
    node: Node,
    url: str,
    source: str,
    base_url: str,
    label: str | None = None,
) -> InstallerCandidate:
    """Combina destino y página base y captura texto del nodo y hasta 500 caracteres de HTML como
    evidencia.

    Args:
        node: Nodo HTML que aporta texto, atributos y contexto del enlace.
        url: URL o ruta del recurso que se interpreta.
        source: Procedencia estable del candidato, como href, formulario o proveedor.
        base_url: Página usada para resolver enlaces relativos y conservar Referer.
        label: Texto explícito de etiqueta; None utiliza el texto del nodo.

    Returns:
        candidato con Referer de la página y URL vacía si no se pudo resolver.
    """
    return InstallerCandidate(
        url=safe_urljoin(base_url, url) or "",
        source=source,
        label=node.text(separator=" ", strip=True) if label is None else label,
        context=(node.html or "")[:500],
        referer=base_url,
    )


def _linked_candidates(parser: HTMLParser, base_url: str) -> Iterator[InstallerCandidate]:
    """Recorre enlaces y áreas antes de formularios, usando href o action y etiqueta accesible
    del formulario cuando existe.

    Args:
        parser: Árbol HTML ya analizado de la página.
        base_url: Página usada para resolver enlaces relativos y conservar Referer.

    Yields:
        candidatos enlazados en el orden de cada grupo de nodos.
    """
    for selector, attribute, source in (("a, area", "href", "href"), ("form", "action", "form")):
        for node in parser.css(selector):
            if url := node.attributes.get(attribute):
                label = node.attributes.get("aria-label") or None if source == "form" else None
                yield _node_candidate(node, url, source, base_url, label)


def _button_candidates(parser: HTMLParser, base_url: str) -> Iterator[InstallerCandidate]:
    """Busca patrones de URL o instalador en texto y atributos de botones y elementos con
    role=button.

    Args:
        parser: Árbol HTML ya analizado de la página.
        base_url: Página usada para resolver enlaces relativos y conservar Referer.

    Yields:
        candidatos encontrados con procedencia button.
    """
    for node in parser.css("button, [role=button]"):
        values = " ".join(value for value in node.attributes.values() if isinstance(value, str))
        text = f"{node.text(separator=' ', strip=True)} {values}"
        for url in URL_PATTERN.findall(text):
            yield _node_candidate(node, url, "button", base_url)


def _attribute_candidates(parser: HTMLParser, base_url: str) -> Iterator[InstallerCandidate]:
    """Busca URL de navegación en atributos de nodos con onclick o datos de enlace/descarga.

    Args:
        parser: Árbol HTML ya analizado de la página.
        base_url: Página usada para resolver enlaces relativos y conservar Referer.

    Yields:
        candidatos con el nombre del atributo como procedencia.
    """
    for node in parser.css(
        "[onclick], [data-url], [data-href], [data-link], [data-download], [data-download-url]"
    ):
        for attribute, value in node.attributes.items():
            if isinstance(value, str):
                for url in navigation_urls_from_attribute(value):
                    yield _node_candidate(node, url, f"attribute:{attribute}", base_url)


def _embedded_candidates(parser: HTMLParser, base_url: str) -> Iterator[InstallerCandidate]:
    """Extrae URL HTTP absolutas de scripts y metadatos, conservando la página como referencia.

    Args:
        parser: Árbol HTML ya analizado de la página.
        base_url: Página usada para resolver enlaces relativos y conservar Referer.

    Yields:
        candidatos embebidos sin ejecutar scripts.
    """
    for selector in ("script", "meta"):
        for node in parser.css(selector):
            content = node.text() if selector == "script" else node.attributes.get("content", "")
            for url in ABSOLUTE_URL_PATTERN.findall(content or ""):
                yield InstallerCandidate(url=url, source=selector, context=url, referer=base_url)


def extract_candidates(html: str, base_url: str) -> list[InstallerCandidate]:
    """Combina extracción de enlaces, botones, atributos y contenido embebido; conserva solo
    HTTP/HTTPS y la primera aparición de cada URL.

    Args:
        html: Contenido HTML del que se extraen posibles destinos de instalación.
        base_url: Página usada para resolver enlaces relativos y conservar Referer.

    Returns:
        candidatos únicos en el orden de descubrimiento.
    """
    parser = HTMLParser(html)
    candidates = chain.from_iterable(
        extractor(parser, base_url)
        for extractor in (
            _linked_candidates,
            _button_candidates,
            _attribute_candidates,
            _embedded_candidates,
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
    """Resuelve una URL relativa sin interrumpir la extracción si la sintaxis provoca ValueError.

    Args:
        base_url: Página usada para resolver enlaces relativos y conservar Referer.
        value: Texto, URL o atributo que se normaliza o analiza.

    Returns:
        destino resuelto o None.
    """
    try:
        return urljoin(base_url, value)
    except ValueError:
        return None


def navigation_urls_from_attribute(value: str) -> list[str]:
    """Reconoce valores de navegación directos y URL entre comillas dentro de atributos y elimina
    duplicados manteniendo orden.

    Args:
        value: Texto, URL o atributo que se normaliza o analiza.

    Returns:
        textos de URL encontrados, aún pendientes de resolver contra la página base.
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
    """Calcula preferencia por formato, palabras clave, identidad, versión y variante de producto
    y devuelve una copia con las evidencias de coincidencia.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.
        app_name: Nombre conocido de la aplicación, o None si falta.
        package_id: Identidad del paquete proveedor, o None si no se conoce.
        publisher: Editor declarado de la aplicación.
        version: Versión esperada, usada como evidencia de coincidencia.

    Returns:
        candidato puntuado sin modificar el original.
    """
    text = normalize_text(f"{candidate.url} {candidate.label or ''} {candidate.context or ''}")
    asset_kind = (
        "source_archive"
        if is_github_source_archive(candidate.url)
        else candidate.asset_kind or classify_asset(candidate.url)
    )
    score = _artifact_score(candidate, asset_kind)
    score += _keyword_score(text, candidate.asset_kind)
    match_tokens = app_match_tokens(
        text=text,
        app_name=app_name,
        package_id=package_id,
        publisher=publisher,
        version=version,
    )
    score += len(match_tokens) * 12
    if version and version_labels_match(extract_version(candidate), version):
        # La versión actual debe llegar antes que el historial cuando el
        # presupuesto de validación es acotado.
        score += 80
    score += variant_score(text=text, app_name=app_name, package_id=package_id)
    return replace(candidate, score=score, asset_kind=asset_kind, match_tokens=tuple(match_tokens))


def _artifact_score(candidate: InstallerCandidate, asset_kind: str | None) -> int:
    """Prioriza formatos de instalador y procedencia Winstall, penaliza archivos fuente y
    distingue ZIP de release o blobs acreditados en GitHub.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.
        asset_kind: Clasificación previa o derivada del artefacto.

    Returns:
        contribución numérica del formato y origen a la puntuación.
    """
    score = 0
    extension = detect_extension(candidate.url)
    if asset_kind == "source_archive":
        score -= 150
    # Una descarga declarada expresamente por Winstall merece llegar a la
    # validación aunque use un endpoint opaco o el nombre contenga una etiqueta
    # como ``beta``. Este bono no publica nada por sí solo: el validador todavía
    # debe observar un artefacto binario seguro y la política del catálogo debe
    # aceptar su identidad y versión.
    if candidate.asset_kind == "winstall_download":
        score += 35
    if extension in (
        WINDOWS_INSTALLER_EXTENSIONS
        + MACOS_INSTALLER_EXTENSIONS
        + (".deb", ".rpm", ".appimage", ".pkg.tar.zst")
    ):
        score += 70
    elif extension == ".zip":
        score += 25
    elif extension in PREFERRED_EXTENSIONS:
        score += 50
    if extension == ".zip" and registered_domain(candidate.url) == "github.com":
        trusted_binary_blob = candidate.asset_kind == "winstall_download" and is_github_raw_file(
            candidate.url
        )
        score += 10 if is_github_release_asset(candidate.url) or trusted_binary_blob else -90
    return score


def _keyword_score(text: str, asset_kind: str | None) -> int:
    """Suma evidencia de arquitectura y descarga y penaliza palabras de documentación, código u
    otras variantes; portable no penaliza una descarga Winstall.

    Args:
        text: Texto normalizado de URL, etiqueta y contexto.
        asset_kind: Clasificación previa o derivada del artefacto.

    Returns:
        contribución de palabras clave a la preferencia.
    """
    score = 0
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
            if keyword == "portable" and asset_kind == "winstall_download":
                continue
            score -= 50
    return score


def classify_asset(url: str) -> str:
    """Distingue archivo fuente GitHub, ZIP de release, instalador de plataforma, archivo
    genérico reconocido y recurso desconocido.

    Args:
        url: URL o ruta del recurso que se interpreta.

    Returns:
        clase de artefacto utilizada para puntuarlo.
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
    """Reconoce codeload, rutas archive/zipball/tarball y los ZIP main o master bajo hosts
    terminados en github.com.

    Args:
        url: URL o ruta del recurso que se interpreta.

    Returns:
        True si la URL apunta a una forma conocida de distribución de código fuente.
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
    """Reconoce la ruta releases/download bajo un netloc terminado en github.com.

    Args:
        url: URL o ruta del recurso que se interpreta.

    Returns:
        True si el enlace tiene estructura de asset de release.
    """
    parsed = urlparse(url)
    return (
        parsed.netloc.lower().endswith("github.com")
        and "/releases/download/" in parsed.path.lower()
    )


def is_github_raw_file(url: str) -> bool:
    """Reconoce raw.githubusercontent.com y rutas raw bajo hosts terminados en github.com.

    Args:
        url: URL o ruta del recurso que se interpreta.

    Returns:
        True si la URL tiene estructura de archivo directo del repositorio.
    """
    parsed = urlparse(url)
    host = parsed.netloc.lower()
    path = parsed.path.lower()
    return host == "raw.githubusercontent.com" or (host.endswith("github.com") and "/raw/" in path)


def app_match_tokens(
    text: str,
    app_name: str | None,
    package_id: str | None,
    publisher: str | None,
    version: str | None,
) -> list[str]:
    """Extrae términos distintivos de nombre, paquete, editor y versión y conserva los presentes
    en el texto candidato.

    Args:
        text: Texto normalizado de URL, etiqueta y contexto.
        app_name: Nombre conocido de la aplicación, o None si falta.
        package_id: Identidad del paquete proveedor, o None si no se conoce.
        publisher: Editor declarado de la aplicación.
        version: Versión esperada, usada como evidencia de coincidencia.

    Returns:
        términos únicos coincidentes.
    """
    raw = " ".join(value for value in (app_name, package_id, publisher, version) if value)
    tokens = product_tokens(raw)
    return [token for token in tokens if token in text]


def product_tokens(value: str) -> list[str]:
    """Normaliza separadores y texto, elimina términos genéricos y conserva tokens alfanuméricos
    de al menos tres caracteres.

    Args:
        value: Texto, URL o atributo que se normaliza o analiza.

    Returns:
        tokens únicos en el orden de aparición.
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


def version_labels_match(first: str | None, second: str | None) -> bool:
    """Compara versiones numéricas sin prefijos habituales ni ceros finales redundantes y utiliza
    comparación textual normalizada como respaldo.

    Args:
        first: Primera versión o etiqueta a comparar.
        second: Segunda versión o etiqueta a comparar.

    Returns:
        True si las etiquetas representan la misma versión; False si falta alguna.
    """
    if not first or not second:
        return False

    def parts(value: str) -> tuple[int, ...] | None:
        """Convierte una versión numérica con puntos en componentes enteros y retira ceros
        finales sin eliminar el único componente.

        Args:
            value: Texto, URL o atributo que se normaliza o analiza.

        Returns:
            tupla numérica normalizada o None si contiene partes no numéricas.
        """
        normalized = value.strip().casefold().removeprefix("version").strip(" :-_")
        if normalized.startswith("v"):
            normalized = normalized[1:]
        raw_parts = normalized.split(".")
        if not raw_parts or not all(part.isdigit() for part in raw_parts):
            return None
        values = [int(part) for part in raw_parts]
        while len(values) > 1 and values[-1] == 0:
            values.pop()
        return tuple(values)

    first_parts = parts(first)
    second_parts = parts(second)
    if first_parts is not None and second_parts is not None:
        return first_parts == second_parts
    return first.strip().casefold() == second.strip().casefold()


def variant_score(text: str, app_name: str | None, package_id: str | None) -> int:
    """Favorece coincidencias de variantes graphing, geometry, cas, suite y classic y penaliza
    candidatos que anuncian una variante no solicitada.

    Args:
        text: Texto normalizado de URL, etiqueta y contexto.
        app_name: Nombre conocido de la aplicación, o None si falta.
        package_id: Identidad del paquete proveedor, o None si no se conoce.

    Returns:
        ajuste de preferencia por variante de producto.
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
    """Delega detección de sufijos simples o compuestos al registro de formatos compartido.

    Args:
        url: URL o ruta del recurso que se interpreta.

    Returns:
        extensión admitida o None.
    """
    return DEFAULT_ARTIFACT_FORMAT_REGISTRY.detect_extension(url)


def filename_from_url(url: str) -> str | None:
    """Busca nombres de instalador en segmentos de ruta y después en parámetros filename, file,
    download o installer.

    Args:
        url: URL o ruta del recurso que se interpreta.

    Returns:
        nombre decodificado de hasta 255 caracteres o None.
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
    """Reúne URL, etiqueta y contexto en un texto normalizado para las reglas de coincidencia.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        texto sin acentos, en minúsculas y con espacios normalizados.
    """
    return normalize_text(f"{candidate.url} {candidate.label or ''} {candidate.context or ''}")


def keyword_present(text: str, keyword: str) -> bool:
    """Busca palabras o frases normalizadas sin incrustarlas dentro de otra palabra alfanumérica
    y admite espacios equivalentes.

    Args:
        text: Texto normalizado de URL, etiqueta y contexto.
        keyword: Palabra o frase que debe aparecer delimitada en el texto.

    Returns:
        True si el marcador aparece delimitado.
    """
    normalized = normalize_text(keyword)
    pattern = re.escape(normalized).replace(r"\ ", r"\s+")
    return re.search(rf"(?<![a-z0-9]){pattern}(?![a-z0-9])", text) is not None


def candidate_has_download_intent(candidate: InstallerCandidate) -> bool:
    """Reconoce la marca de descarga Winstall o palabras delimitadas de descarga e instalación en
    la evidencia del candidato.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        True si la página sugiere una acción de descarga.
    """
    text = candidate_text(candidate)
    if candidate.asset_kind == "winstall_download":
        return True
    return any(
        keyword_present(text, keyword)
        for keyword in ("download", "descargar", "installer", "instalador", "setup", "install")
    )


def is_download_candidate(candidate: InstallerCandidate) -> bool:
    """Acepta para evaluación destinos con extensión conocida o intención textual de descarga.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        True si merece pasar a puntuación y validación posterior.
    """
    return bool(detect_extension(candidate.url)) or candidate_has_download_intent(candidate)


def candidate_variants(candidate: InstallerCandidate) -> list[InstallerCandidate]:
    """Conserva el candidato original y añade alternativas HTTPS, Elcomsoft, S3 y SourceForge en
    ese orden, sin repetir URL.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        candidatos de rutas alternativas que todavía deben validarse.
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
    """Propone la misma URL con HTTPS para un destino HTTP con host y marca la procedencia de la
    transformación.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        variante HTTPS o None.
    """
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
    """Deriva el host canónico download.elcomsoft.com para rutas de descarga con formato conocido
    en los hosts Elcomsoft contemplados.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        candidato canónico con consulta conservada o None.
    """
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
    """Reescribe buckets con guion bajo de la forma bucket.s3.amazonaws.com a una ruta de
    s3.amazonaws.com.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        variante HTTPS por ruta que conserva la consulta o None.
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
    """Deriva la ruta central downloads.sourceforge.net desde una ficha de archivos o un espejo
    dl.sourceforge.net.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        candidato de enrutamiento de espejos o None.
    """
    parsed = urlparse(candidate.url)
    host = (parsed.hostname or "").lower()
    if host in {"sourceforge.net", "www.sourceforge.net"}:
        match = re.fullmatch(
            r"/projects?/([^/]+)/files/(.+?)(?:/download)?/?",
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
    """Prefiere la plataforma del formato y utiliza texto de nombre y contexto para casos
    ambiguos; tar.gz se considera Linux salvo evidencia macOS.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        windows, macos, linux o None.
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
    """Consulta la plataforma única asociada a un formato en el registro compartido.

    Args:
        extension: Extensión completa del artefacto, o None si no se conoce.

    Returns:
        nombre de plataforma o None para formatos ambiguos o desconocidos.
    """
    platform = DEFAULT_ARTIFACT_FORMAT_REGISTRY.platform_for(extension)
    return platform.value if platform else None


def infer_architecture(candidate: InstallerCandidate) -> str:
    """Busca marcadores de arquitectura en la evidencia normalizada del candidato y conserva
    x86_64 como valor predeterminado histórico.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        arquitectura normalizada.
    """
    text = candidate_text(candidate)
    return DEFAULT_ARTIFACT_FORMAT_REGISTRY.infer_architecture(
        text,
        default=ArtifactArchitecture.X86_64,
    ).value


def has_architecture_token(text: str, token: str) -> bool:
    """Busca un marcador de arquitectura delimitado por caracteres no alfanuméricos.

    Args:
        text: Texto normalizado de URL, etiqueta y contexto.
        token: Marcador literal de arquitectura.

    Returns:
        True si el texto contiene el token completo.
    """
    return re.search(rf"(?<![a-z0-9]){re.escape(token)}(?![a-z0-9])", text) is not None


def extract_version(candidate: InstallerCandidate) -> str | None:
    """Busca versiones con puntos en ruta, consulta, fragmento, etiqueta y contexto, excluyendo
    autoridades URL para evitar números del dominio.

    Args:
        candidate: Candidato original con URL y evidencia de procedencia.

    Returns:
        última versión numérica encontrada o None.
    """
    try:
        parsed = urlparse(candidate.url)
        url_text = " ".join((parsed.path, parsed.query, parsed.fragment))
    except ValueError:
        url_text = candidate.url
    supporting_text = " ".join(
        strip_url_authorities(value) for value in (candidate.label, candidate.context) if value
    )
    raw = f"{url_text} {supporting_text}"
    matches = VERSION_PATTERN.findall(raw)
    if not matches:
        return None
    return matches[-1]


def strip_url_authorities(value: str) -> str:
    """Retira esquema y autoridad de enlaces embebidos para que hosts y puertos no se interpreten
    como versiones.

    Args:
        value: Texto, URL o atributo que se normaliza o analiza.

    Returns:
        texto restante con rutas y contexto conservados.
    """
    return re.sub(r"https?://(?:\[[^\]]+\]|[^/\s'\"<>]+)", "", value, flags=re.I)


def registered_domain(url: str) -> str | None:
    """Extrae dominio y sufijo público y descarta subdominios para comparar procedencia.

    Args:
        url: URL o ruta del recurso que se interpreta.

    Returns:
        dominio registrado en minúsculas o None si no existe dominio con sufijo reconocido.
    """
    import tldextract

    extracted = tldextract.extract(url)
    if not extracted.domain or not extracted.suffix:
        return None
    return f"{extracted.domain}.{extracted.suffix}".lower()
