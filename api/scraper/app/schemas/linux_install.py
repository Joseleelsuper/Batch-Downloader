"""Valida recetas Linux declarativas, orígenes de actualización y claves públicas antes de
incorporarlas al runtime offline.
"""

from __future__ import annotations

import re
from typing import Literal, cast
from urllib.parse import urlsplit
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

Target = Literal["apt", "dnf", "pacman", "zypper", "portable"]
Strategy = Literal["deb", "rpm", "arch", "appimage", "tarball", "jar", "manual"]


class StrictModel(BaseModel):
    """Rechaza campos desconocidos y permite nombres Python o alias JSON en los contratos
    administrativos Linux.
    """
    model_config = ConfigDict(extra="forbid", populate_by_name=True)


class UpdatePolicy(StrictModel):
    """Limita de dónde puede obtener el instalador una actualización y qué artefactos, huellas y
    firmas debe seleccionar.

    Attributes:
        provider: github exige repositorio, patrón de artefacto y api.github.com; official
            exige manifiesto HTTPS aprobado.
        url, repository: Manifiesto oficial o identidad owner/repository de GitHub.
        asset_pattern, checksum_pattern, signature_pattern: Patrones declarativos de
            selección; no contienen comandos de ejecución.
        allowed_hosts: Lista de uno a doce hosts admitidos, deduplicada conservando orden.
    """
    provider: Literal["github", "official"]
    url: str | None = Field(default=None, max_length=2048)
    repository: str | None = Field(default=None, pattern=r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    asset_pattern: str | None = Field(default=None, alias="assetPattern", max_length=200)
    checksum_pattern: str | None = Field(default=None, alias="checksumPattern", max_length=200)
    signature_pattern: str | None = Field(default=None, alias="signaturePattern", max_length=200)
    allowed_hosts: list[str] = Field(alias="allowedHosts", min_length=1, max_length=12)

    @field_validator("allowed_hosts")
    @classmethod
    def hosts(cls, values: list[str]) -> list[str]:
        """Rechaza nombres fuera de la sintaxis admitida en minúsculas y elimina repetidos sin
        alterar su orden.

        Args:
            values: Colección recibida que se valida antes de construir el perfil.

        Returns:
            hosts admitidos únicos.

        Raises:
            ValueError: invalid_update_host si algún nombre incumple la expresión regular.
        """
        if any(not re.fullmatch(r"[a-z0-9][a-z0-9.-]+", v) for v in values):
            raise ValueError("invalid_update_host")
        return list(dict.fromkeys(values))

    @model_validator(mode="after")
    def provider_fields(self) -> UpdatePolicy:
        """Exige los datos necesarios para el proveedor elegido y valida el origen del manifiesto
        oficial.

        Returns:
            política validada.

        Raises:
            ValueError: Si faltan repositorio, patrón o host de GitHub, o la URL oficial no es
                HTTPS con host aprobado.
        """
        if self.provider == "github":
            if (
                not self.repository
                or not self.asset_pattern
                or "api.github.com" not in self.allowed_hosts
            ):
                raise ValueError("github_repository_asset_and_host_required")
        elif not self.url or not approved_url(self.url, self.allowed_hosts):
            raise ValueError("official_https_manifest_required")
        return self


def approved_url(value: str, hosts: list[str]) -> bool:
    """Comprueba HTTPS, host explícitamente permitido, puerto 443 o implícito y ausencia de
    credenciales, fragmento o controles.

    Args:
        value: URL completa cuyo origen se compara con la lista aprobada.
        hosts: Nombres de host aprobados para la política de actualización.

    Returns:
        False ante una URL inválida o no aprobada; True si cumple todas las restricciones.
    """
    try:
        url = urlsplit(value)
        return (
            url.scheme == "https"
            and url.hostname in hosts
            and url.port in (None, 443)
            and not url.username
            and not url.password
            and not url.fragment
            and not any(ord(c) < 32 for c in value)
        )
    except ValueError:
        return False


class Verification(StrictModel):
    """Incluye la clave pública y huella con las que el runtime comprobará la firma del
    artefacto.

    Attributes:
        fingerprint: Huella hexadecimal de 40 o 64 caracteres.
        public_key: Bloque de clave pública PGP de hasta 65536 caracteres.
        signature_url: URL opcional de firma separada; su origen se valida junto con la
            receta.
    """
    fingerprint: str = Field(pattern=r"^(?:[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64})$")
    public_key: str = Field(alias="publicKey", max_length=65536)
    signature_url: str | None = Field(default=None, alias="signatureUrl", max_length=2048)

    @field_validator("public_key")
    @classmethod
    def public_only(cls, value: str) -> str:
        """Exige un bloque identificado como clave pública y rechaza texto que contenga PRIVATE.

        Args:
            value: Valor opcional recibido para el campo que se valida.

        Returns:
            texto de clave recibido.

        Raises:
            ValueError: public_key_required si no contiene el marcador público o parece
                incluir material privado.
        """
        if "BEGIN PGP PUBLIC KEY BLOCK" not in value or "PRIVATE" in value:
            raise ValueError("public_key_required")
        return value


class LinuxInstallProfile(StrictModel):
    """Describe estrategia, ámbito, dependencias y verificación de una instalación sin admitir
    comandos arbitrarios.

    Attributes:
        schema_version: Versión uno del contrato del runtime.
        strategy, scope: Formato o tratamiento manual y ámbito auto, user o system.
        entrypoint: Ruta relativa del ejecutable o JAR; obligatoria para tarball y jar.
        dependencies: UUID de aplicaciones necesarias, hasta cien.
        system_packages, linux_targets: Paquetes por gestor y destinos Linux compatibles.
        desktop_name: Nombre opcional de la entrada de escritorio, sin caracteres de control.
        update, verification: Origen de actualizaciones y confianza de firma opcionales.
    """
    schema_version: Literal[1] = Field(default=1, alias="schemaVersion")
    strategy: Strategy
    scope: Literal["auto", "user", "system"] = "auto"
    entrypoint: str | None = Field(default=None, max_length=512)
    dependencies: list[UUID] = Field(default_factory=list, max_length=100)
    system_packages: dict[Target, list[str]] = Field(default_factory=dict, alias="systemPackages")
    linux_targets: list[Target] = Field(default_factory=list, alias="linuxTargets")
    desktop_name: str | None = Field(default=None, alias="desktopName", max_length=200)
    update: UpdatePolicy | None = None
    verification: Verification | None = None

    @field_validator("entrypoint")
    @classmethod
    def relative_entrypoint(cls, value: str | None) -> str | None:
        """Rechaza rutas absolutas, barras inversas, dos puntos, segmentos .. y caracteres de
        control en el punto de entrada.

        Args:
            value: Valor opcional recibido para el campo que se valida.

        Returns:
            ruta relativa recibida o None.

        Raises:
            ValueError: unsafe_entrypoint si la ruta incumple las restricciones.
        """
        if value and (
            value.startswith("/")
            or "\\" in value
            or ":" in value
            or ".." in value.split("/")
            or any(ord(c) < 32 for c in value)
        ):
            raise ValueError("unsafe_entrypoint")
        return value

    @field_validator("system_packages")
    @classmethod
    def package_names(cls, values: dict[str, list[str]]) -> dict[str, list[str]]:
        """Limita a cincuenta paquetes por gestor y valida cada nombre antes de pasarlo al
        instalador nativo.

        Args:
            values: Colección recibida que se valida antes de construir el perfil.

        Returns:
            mapa original con nombres validados.

        Raises:
            ValueError: invalid_system_package si se supera el máximo o un nombre no cumple la
                sintaxis admitida.
        """
        if any(
            len(packages) > 50
            or any(not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9+._:-]{0,127}", p) for p in packages)
            for packages in values.values()
        ):
            raise ValueError("invalid_system_package")
        return values

    @field_validator("desktop_name")
    @classmethod
    def safe_name(cls, value: str | None) -> str | None:
        """Impide caracteres de control en el nombre que se incorporará a la entrada de
        escritorio.

        Args:
            value: Valor opcional recibido para el campo que se valida.

        Returns:
            nombre original o None.

        Raises:
            ValueError: invalid_desktop_name si contiene caracteres inferiores a U+0020.
        """
        if value and any(ord(c) < 32 for c in value):
            raise ValueError("invalid_desktop_name")
        return value

    @model_validator(mode="after")
    def recipe(self) -> LinuxInstallProfile:
        """Exige punto de entrada para tarball o jar y vincula cualquier URL de firma a los hosts
        aprobados para actualización.

        Returns:
            receta validada.

        Raises:
            ValueError: Si falta un punto de entrada requerido o la firma no procede de un
                origen aprobado.
        """
        if self.strategy in ("tarball", "jar") and not self.entrypoint:
            raise ValueError("recipe_entrypoint_required")
        if self.verification and self.verification.signature_url:
            if not self.update or not approved_url(
                self.verification.signature_url, self.update.allowed_hosts
            ):
                raise ValueError("signature_origin_not_approved")
        return self


class ProfileWrite(StrictModel):
    """Solicita guardar una receta borrador o aprobada con control de versión optimista.

    Attributes:
        expected_version: Versión no negativa que debe coincidir con la persistida.
        status: draft o approved según la revisión administrativa.
        profile: Receta validada que se guardará.
    """
    expected_version: int = Field(alias="expectedVersion", ge=0)
    status: Literal["draft", "approved"]
    profile: LinuxInstallProfile


class DependenciesWrite(StrictModel):
    """Reemplaza las dependencias directas de una aplicación con control de cambios concurrentes.

    Attributes:
        dependencies: Hasta 99 UUID de aplicaciones dependientes.
        expected_version: Versión no negativa esperada de la lista persistida.
    """
    dependencies: list[UUID] = Field(max_length=99)
    expected_version: int = Field(alias="expectedVersion", ge=0)


FORMAT_STRATEGIES = {
    ".deb": "deb",
    ".rpm": "rpm",
    ".pkg.tar.zst": "arch",
    ".appimage": "appimage",
    ".tar.gz": "tarball",
    ".jar": "jar",
}
FORMAT_TARGETS = {
    ".deb": ["apt"],
    ".rpm": ["dnf", "zypper"],
    ".pkg.tar.zst": ["pacman"],
    ".appimage": ["apt", "dnf", "pacman", "zypper", "portable"],
    ".tar.gz": ["apt", "dnf", "pacman", "zypper", "portable"],
    ".jar": ["apt", "dnf", "pacman", "zypper", "portable"],
}


def default_profile(extension: str | None) -> dict:
    """Deriva una estrategia inicial del formato; tarball y jar permanecen manuales hasta
    disponer de una receta con punto de entrada.

    Args:
        extension: Extensión del artefacto, incluida la forma compuesta; None produce
            estrategia manual.

    Returns:
        perfil JSON versión uno con alias públicos y sin campos None.
    """
    strategy = FORMAT_STRATEGIES.get((extension or "").lower(), "manual")
    if strategy in ("tarball", "jar"):
        strategy = "manual"
    return LinuxInstallProfile(strategy=cast(Strategy, strategy)).model_dump(
        by_alias=True, mode="json", exclude_none=True
    )
