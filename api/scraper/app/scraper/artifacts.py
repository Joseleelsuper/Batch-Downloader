"""Centraliza formatos binarios, plataformas, MIME y prefijos de bytes utilizados para reconocer
instaladores.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from enum import StrEnum
from pathlib import PurePosixPath
from urllib.parse import parse_qs, unquote, urlparse


class ArtifactPlatform(StrEnum):
    """Enumera las tres plataformas de catálogo con las que se asocian los formatos reconocidos."""
    WINDOWS = "windows"

    MACOS = "macos"

    LINUX = "linux"



class ArtifactArchitecture(StrEnum):
    """Normaliza arquitecturas ARM64, x86 y x86_64 y representa como unknown la falta de
    evidencia.
    """
    X86_64 = "x86_64"

    X86 = "x86"

    AARCH64 = "aarch64"

    UNKNOWN = "unknown"



@dataclass(frozen=True)
class ArtifactFormat:
    """Declara cómo reconocer un formato y si sus bytes permiten inferirlo sin conocer la
    extensión.

    Attributes:
        extension: Sufijo completo, incluido el punto y las extensiones compuestas.
        platforms: Plataformas asociadas; vacío no atribuye una plataforma única.
        media_types: Tipos MIME específicos reconocidos.
        signatures: Prefijos de bytes del formato, no firmas criptográficas del editor.
        infer_from_signature: True permite escoger este formato al inferirlo solo por bytes.
    """
    extension: str

    platforms: tuple[ArtifactPlatform, ...]

    media_types: tuple[str, ...] = ()

    signatures: tuple[bytes, ...] = ()

    infer_from_signature: bool = False



class ArtifactFormatRegistry:
    """Mantiene el orden de formatos y resuelve extensión, plataforma y arquitectura con las
    mismas reglas para los distintos resolutores.

    See Also:
        app.scraper.validator.DownloadValidator: Comprueba evidencia binaria mediante este
            registro.
    """

    def __init__(self, formats: tuple[ArtifactFormat, ...]) -> None:
        """Indexa los formatos por extensión y rechaza un registro vacío o con sufijos repetidos.

        Args:
            formats: Formatos admitidos en orden de preferencia; deben ser no vacíos y tener
                extensiones únicas.

        Raises:
            ValueError: Si no hay formatos o existen extensiones duplicadas.
        """
        if not formats:
            raise ValueError("artifact_format_registry_cannot_be_empty")
        self._formats = formats

        self._by_extension = {item.extension: item for item in formats}

        if len(self._by_extension) != len(formats):
            raise ValueError("artifact_format_extensions_must_be_unique")

    @property
    def extensions(self) -> tuple[str, ...]:
        """Expone extensiones admitidas conservando el orden declarado del registro.

        Returns:
            tupla de sufijos completos.
        """
        return tuple(item.extension for item in self._formats)

    @property
    def binary_media_types(self) -> frozenset[str]:
        """Reúne los tipos MIME específicos de todos los formatos sin duplicados.

        Returns:
            conjunto inmutable de tipos binarios.
        """
        return frozenset(
            media_type
            for artifact_format in self._formats
            for media_type in artifact_format.media_types
        )

    def get(self, extension: str | None) -> ArtifactFormat | None:
        """Busca un formato por extensión recortada y en minúsculas; no añade un punto ausente.

        Args:
            extension: Extensión, incluido el punto inicial, o None cuando no se conoce.

        Returns:
            formato reconocido o None.
        """
        if not extension:
            return None
        return self._by_extension.get(extension.lower().strip())

    def extensions_for(self, platform: ArtifactPlatform | str) -> tuple[str, ...]:
        """Filtra extensiones de los formatos asociados a una plataforma conservando el orden del
        registro.

        Args:
            platform: Plataforma windows, macos o linux.

        Returns:
            extensiones de esa plataforma.

        Raises:
            ValueError: Si el nombre no corresponde a una plataforma admitida.
        """
        normalized = ArtifactPlatform(platform)
        return tuple(
            item.extension for item in self._formats if normalized in item.platforms
        )

    def platform_for(self, extension: str | None) -> ArtifactPlatform | None:
        """Asigna plataforma solo cuando el formato está vinculado exactamente a una.

        Args:
            extension: Extensión, incluido el punto inicial, o None cuando no se conoce.

        Returns:
            plataforma inequívoca o None.
        """
        artifact_format = self.get(extension)
        if artifact_format is None or len(artifact_format.platforms) != 1:
            return None
        return artifact_format.platforms[0]

    def detect_extension(self, value: str) -> str | None:
        """Busca sufijos conocidos en segmentos de ruta decodificados y después en valores de
        consulta de forma recursiva; prioriza sufijos más largos.

        Args:
            value: URL, ruta o valor de consulta que puede contener una extensión.

        Returns:
            primera extensión reconocida o None.
        """
        try:
            parsed = urlparse(value)
        except ValueError:
            return None
        path = unquote(parsed.path).lower()
        for segment in reversed([path, *path.split("/")]):
            for extension in sorted(self.extensions, key=len, reverse=True):
                if segment.endswith(extension):
                    return extension
            suffix = PurePosixPath(segment).suffix
            if suffix in self._by_extension:
                return suffix
        for values in parse_qs(parsed.query).values():
            for nested_value in values:
                nested = self.detect_extension(nested_value)
                if nested:
                    return nested
        return None

    def matches_signature(self, extension: str, content: bytes) -> bool:
        """Compara el inicio del contenido con los prefijos binarios configurados para el
        formato.

        Args:
            extension: Extensión, incluido el punto inicial, o None cuando no se conoce.
            content: Bytes iniciales del archivo utilizados para reconocer su formato.

        Returns:
            True si coincide un prefijo; False si falta formato, contenido o coincidencia.
        """
        artifact_format = self.get(extension)
        if artifact_format is None or not content:
            return False
        return any(content.startswith(signature) for signature in artifact_format.signatures)

    def infer_extension(self, content: bytes) -> str | None:
        """Recorre en orden formatos que permiten inferencia binaria y escoge el primero cuyo
        prefijo coincide.

        Args:
            content: Bytes iniciales del archivo utilizados para reconocer su formato.

        Returns:
            extensión inferida o None.
        """
        for artifact_format in self._formats:
            if artifact_format.infer_from_signature and self.matches_signature(
                artifact_format.extension,
                content,
            ):
                return artifact_format.extension
        return None

    def infer_architecture(
        self,
        text: str,
        *,
        default: ArtifactArchitecture = ArtifactArchitecture.UNKNOWN,
    ) -> ArtifactArchitecture:
        """Busca marcadores completos sin distinguir mayúsculas y prioriza ARM64, x86_64 y x86 en
        ese orden.

        Args:
            text: Texto de nombre, etiqueta o contexto donde se busca arquitectura.
            default: Arquitectura que se conserva cuando no hay marcadores reconocidos.

        Returns:
            arquitectura inferida o la predeterminada recibida.
        """
        normalized_text = text.casefold()
        token_groups = (
            (
                ArtifactArchitecture.AARCH64,
                ("aarch64", "arm64", "apple silicon", "m1", "m2", "m3"),
            ),
            (
                ArtifactArchitecture.X86_64,
                ("x86_64", "amd64", "x64", "win64", "64-bit", "64bit"),
            ),
            (
                ArtifactArchitecture.X86,
                ("i386", "i686", "x86", "win32", "32-bit", "32bit"),
            ),
        )
        for architecture, tokens in token_groups:
            if any(_has_token(normalized_text, token) for token in tokens):
                return architecture
        return default


def _has_token(text: str, token: str) -> bool:
    """Busca un marcador literal sin que forme parte de una palabra alfanumérica más larga.

    Args:
        text: Texto de nombre, etiqueta o contexto donde se busca arquitectura.
        token: Marcador literal que debe aparecer delimitado por caracteres no alfanuméricos.

    Returns:
        True si el texto contiene una coincidencia delimitada.
    """
    return re.search(rf"(?<![a-z0-9]){re.escape(token)}(?![a-z0-9])", text) is not None


DEFAULT_ARTIFACT_FORMAT_REGISTRY = ArtifactFormatRegistry(
    (
        ArtifactFormat(
            ".exe",
            (ArtifactPlatform.WINDOWS,),
            (
                "application/exe",
                "application/x-dosexec",
                "application/x-executable",
                "application/x-ms-dos-executable",
                "application/x-msdos-program",
                "application/x-msdownload",
                "application/vnd.microsoft.portable-executable",
            ),
            (b"MZ",),
            True,
        ),
        ArtifactFormat(
            ".msi",
            (ArtifactPlatform.WINDOWS,),
            (
                "application/x-msi",
                "application/x-ms-installer",
                "application/x-ole-storage",
            ),
            (b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1",),
            True,
        ),
        ArtifactFormat(
            ".msix",
            (ArtifactPlatform.WINDOWS,),
            ("application/msix", "application/zip"),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".msixbundle",
            (ArtifactPlatform.WINDOWS,),
            ("application/msixbundle", "application/zip"),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".appx",
            (ArtifactPlatform.WINDOWS,),
            ("application/appx", "application/zip"),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".appxbundle",
            (ArtifactPlatform.WINDOWS,),
            ("application/appxbundle", "application/zip"),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".zip",
            (),
            ("application/zip", "application/x-zip-compressed"),
            (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"),
            True,
        ),
        ArtifactFormat(
            ".deb",
            (ArtifactPlatform.LINUX,),
            ("application/x-debian-package",),
            (b"!<arch>\n",),
            True,
        ),
        ArtifactFormat(
            ".rpm",
            (ArtifactPlatform.LINUX,),
            ("application/x-rpm",),
            (b"\xed\xab\xee\xdb",),
            True,
        ),
        ArtifactFormat(
            ".appimage",
            (ArtifactPlatform.LINUX,),
            ("application/x-elf",),
            (b"\x7fELF",),
            True,
        ),
        ArtifactFormat(
            ".dmg",
            (ArtifactPlatform.MACOS,),
            ("application/x-apple-diskimage",),
        ),
        ArtifactFormat(
            ".pkg",
            (ArtifactPlatform.MACOS,),
            ("application/x-xar",),
            (b"xar!",),
            True,
        ),
        ArtifactFormat(
            ".tar.gz",
            (ArtifactPlatform.LINUX,),
            ("application/gzip", "application/x-gzip"),
            (b"\x1f\x8b",),
            True,
        ),
        ArtifactFormat(
            ".jar",
            (ArtifactPlatform.LINUX,),
            ("application/java-archive",),
            (b"PK\x03\x04",),
        ),
        ArtifactFormat(
            ".pkg.tar.zst", (ArtifactPlatform.LINUX,),
            ("application/zstd", "application/x-zstd"), (b"\x28\xb5\x2f\xfd",),
        ),
    )
)


GENERIC_BINARY_MEDIA_TYPES = frozenset({"application/octet-stream", "binary/octet-stream"})

