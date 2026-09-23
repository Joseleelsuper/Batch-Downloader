"""Despliega una versión inmutable en Coolify y revierte ante cualquier fallo."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit
from urllib.request import Request, urlopen

from scripts.deployment.production_smoke import DEFAULT_URLS, run_smoke

SHA_PATTERN = re.compile(r"[0-9a-f]{40}")
TAG_PATTERN = re.compile(r"sha-[0-9a-f]{40}")
SUCCESS_STATES = frozenset({"finished", "success", "succeeded", "completed"})
FAILURE_STATES = frozenset(
    {"failed", "failure", "cancelled", "canceled", "cancelled-by-user", "error"}
)

REQUIRED_VALUES = frozenset(
    {
        "GHCR_REGISTRY",
        "GHCR_OWNER",
        "GHCR_IMAGE_PREFIX",
        "GHCR_IMAGE_TAG",
        "MYSQL_ROOT_PASSWORD",
        "MYSQL_DATABASE",
        "MYSQL_USER",
        "MYSQL_PASSWORD",
        "POSTGRES_DB",
        "POSTGRES_USER",
        "POSTGRES_PASSWORD",
        "RABBITMQ_DEFAULT_USER",
        "RABBITMQ_DEFAULT_PASS",
        "MINIO_ROOT_USER",
        "MINIO_ROOT_PASSWORD",
        "MINIO_CORE_ACCESS_KEY",
        "MINIO_CORE_SECRET_KEY",
        "MINIO_WORKER_ACCESS_KEY",
        "MINIO_WORKER_SECRET_KEY",
        "CORE_API_ADMIN_USERNAME",
        "CORE_API_ADMIN_EMAIL",
        "CORE_API_ADMIN_PASSWORD",
        "CORE_API_FLYWAY_TARGET",
        "NOTIFICATION_TOKEN_ENCRYPTION_KEY",
        "SCRAPER_ALEMBIC_TARGET",
        "SCRAPER_INTERNAL_SERVICE_TOKEN",
        "SCRAPER_URL_PROTECTION_SECRET",
        "CORE_API_DOWNLOAD_OWNER_SECRET",
        "RESEND_API_KEY",
        "RESEND_FROM_EMAIL",
        "SEMANTIC_MODELS_VOLUME_NAME",
    }
)

FIXED_PRODUCTION_VALUES = {
    "GHCR_REGISTRY": "ghcr.io",
    "GHCR_OWNER": "joseleelsuper",
    "GHCR_IMAGE_PREFIX": "batch-downloader",
    "APP_PUBLIC_BASE_URL": "https://batchdownloader.dev",
    "MINIO_PUBLIC_ENDPOINT": "https://downloads.batchdownloader.dev",
    "MINIO_ZIP_QUOTA": "10GB",
    "CORE_API_REQUIRE_HTTPS": "true",
    "CORE_API_COOKIE_SECURE": "true",
    "RESEND_FROM_EMAIL": "no-reply@batchdownloader.dev",
    "DOWNLOAD_WORKER_MAX_TOTAL_SIZE": "8GB",
    "DOWNLOAD_WORKER_MIN_FREE_SPACE": "8GB",
    "SEMANTIC_MODELS_VOLUME_NAME": "batch-downloader_semantic_models",
}

SCHEMA_TARGET_KEYS = ("CORE_API_FLYWAY_TARGET", "SCRAPER_ALEMBIC_TARGET")


class ReleaseError(RuntimeError):
    """Representa un fallo controlado de configuración, API o despliegue."""


@dataclass(frozen=True)
class ReleaseState:
    commit: str
    image_tag: str

    @property
    def rollback_capable(self) -> bool:
        return bool(SHA_PATTERN.fullmatch(self.commit) and TAG_PATTERN.fullmatch(self.image_tag))


class CoolifyClient:
    """Cliente mínimo de la API pública de Coolify, sin dependencias externas."""

    def __init__(self, base_url: str, token: str, application_uuid: str, timeout: float = 30):
        parsed = urlsplit(base_url)
        if parsed.scheme != "https" or not parsed.netloc:
            raise ReleaseError("COOLIFY_URL debe ser una URL HTTPS absoluta")
        if not token.strip() or not application_uuid.strip():
            raise ReleaseError("COOLIFY_TOKEN y COOLIFY_APP_UUID son obligatorios")
        normalized = base_url.rstrip("/")
        self.api_url = normalized if normalized.endswith("/api/v1") else f"{normalized}/api/v1"
        self.token = token
        self.application_uuid = application_uuid
        self.timeout = timeout

    def request(
        self,
        method: str,
        path: str,
        payload: Mapping[str, Any] | None = None,
        query: Mapping[str, str] | None = None,
    ) -> Any:
        url = f"{self.api_url}{path}"
        if query:
            url = f"{url}?{urlencode(query)}"
        body = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(
            url,
            data=body,
            method=method,
            headers={
                "Accept": "application/json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
                "User-Agent": "batch-downloader-release/1",
            },
        )
        try:
            with urlopen(request, timeout=self.timeout) as response:  # noqa: S310
                response_body = response.read()
        except HTTPError as exception:
            detail = exception.read(512).decode("utf-8", errors="replace")
            raise ReleaseError(
                f"Coolify respondió HTTP {exception.code} en {method} {path}: {detail}"
            ) from exception
        except URLError as exception:
            raise ReleaseError(f"No se pudo contactar con Coolify: {exception.reason}") from exception
        if not response_body:
            return None
        try:
            return json.loads(response_body)
        except json.JSONDecodeError as exception:
            raise ReleaseError(f"Coolify no devolvió JSON en {method} {path}") from exception

    def application(self) -> Mapping[str, Any]:
        result = self.request("GET", f"/applications/{self.application_uuid}")
        if not isinstance(result, Mapping):
            raise ReleaseError("La aplicación de Coolify tiene una respuesta inesperada")
        return result

    def environment(self) -> dict[str, str]:
        result = self.request("GET", f"/applications/{self.application_uuid}/envs")
        if not isinstance(result, list):
            raise ReleaseError("Coolify no devolvió la lista de variables de la aplicación")
        values: dict[str, str] = {}
        for item in result:
            if not isinstance(item, Mapping) or not isinstance(item.get("key"), str):
                continue
            if item.get("is_preview") is True:
                continue
            value = item.get("value", item.get("real_value", ""))
            if item.get("is_shown_once") is True and not str(value or "").strip():
                value = "configured-secret"
            values[item["key"]] = value if isinstance(value, str) else str(value)
        return values

    def update_commit(self, commit: str) -> None:
        self.request(
            "PATCH",
            f"/applications/{self.application_uuid}",
            {"git_commit_sha": commit},
        )

    def update_image_tag(self, image_tag: str) -> None:
        self.request(
            "PATCH",
            f"/applications/{self.application_uuid}/envs",
            {"key": "GHCR_IMAGE_TAG", "value": image_tag, "is_preview": False},
        )

    def deploy(self) -> str:
        result = self.request(
            "POST",
            "/deploy",
            query={"uuid": self.application_uuid},
        )
        deployments = result.get("deployments") if isinstance(result, Mapping) else None
        if not isinstance(deployments, list) or not deployments:
            raise ReleaseError("Coolify no devolvió el identificador del despliegue")
        deployment_uuid = deployments[0].get("deployment_uuid")
        if not isinstance(deployment_uuid, str) or not deployment_uuid:
            raise ReleaseError("Coolify devolvió un identificador de despliegue inválido")
        return deployment_uuid

    def deployment(self, deployment_uuid: str) -> Mapping[str, Any]:
        result = self.request("GET", f"/deployments/{deployment_uuid}")
        if not isinstance(result, Mapping):
            raise ReleaseError("Coolify devolvió un estado de despliegue inesperado")
        return result


def repository_schema_targets(configuration_path: Path | None = None) -> dict[str, str]:
    """Lee los objetivos de esquema del único fichero de configuración versionado."""
    path = configuration_path or Path(__file__).resolve().parents[2] / ".env.example"
    configured: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exception:
        raise ReleaseError(f"No se pudo leer la configuración de esquema {path}") from exception

    for line_number, line in enumerate(lines, start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        key, separator, value = stripped.partition("=")
        key = key.strip()
        if key not in SCHEMA_TARGET_KEYS:
            continue
        if not separator or not value.strip():
            raise ReleaseError(f"{path}:{line_number}: falta el valor de {key}")
        if key in configured:
            raise ReleaseError(f"{path}:{line_number}: {key} aparece más de una vez")
        configured[key] = value.strip()

    missing = sorted(set(SCHEMA_TARGET_KEYS) - configured.keys())
    if missing:
        raise ReleaseError(f"Faltan objetivos de esquema en {path}: {', '.join(missing)}")
    return configured


def validate_environment(
    values: Mapping[str, str],
    expected_tag: str | None = None,
    schema_targets: Mapping[str, str] | None = None,
) -> None:
    """Rechaza secretos vacíos y cualquier desviación de la política de producción."""
    missing = sorted(key for key in REQUIRED_VALUES if not values.get(key, "").strip())
    if missing:
        raise ReleaseError(f"Variables de producción vacías o ausentes: {', '.join(missing)}")
    expected_schema = (
        dict(schema_targets) if schema_targets is not None else repository_schema_targets()
    )
    missing_schema = sorted(set(SCHEMA_TARGET_KEYS) - expected_schema.keys())
    if missing_schema:
        raise ReleaseError(f"Faltan objetivos esperados de esquema: {', '.join(missing_schema)}")
    wrong_schema = [
        f"{key} (esperado {expected_schema[key]})"
        for key in SCHEMA_TARGET_KEYS
        if values.get(key) != expected_schema[key]
    ]
    if wrong_schema:
        raise ReleaseError(
            "Los objetivos de esquema de Coolify no coinciden con .env.example: "
            + ", ".join(wrong_schema)
        )
    wrong = [
        key
        for key, expected in FIXED_PRODUCTION_VALUES.items()
        if values.get(key) != expected
    ]
    if wrong:
        raise ReleaseError(f"Variables de producción con valor no permitido: {', '.join(sorted(wrong))}")
    tag = values.get("GHCR_IMAGE_TAG", "")
    if expected_tag is not None and tag != expected_tag:
        raise ReleaseError(f"GHCR_IMAGE_TAG debe ser {expected_tag}")
    if not TAG_PATTERN.fullmatch(tag):
        raise ReleaseError("GHCR_IMAGE_TAG debe tener el formato sha-<commit de 40 caracteres>")


def wait_for_deployment(
    client: CoolifyClient,
    deployment_uuid: str,
    *,
    timeout: float,
    interval: float,
) -> None:
    deadline = time.monotonic() + timeout
    last_status = ""
    while time.monotonic() < deadline:
        deployment = client.deployment(deployment_uuid)
        status = str(deployment.get("status", "")).strip().lower()
        if status != last_status:
            print(f"Coolify: despliegue {deployment_uuid} en estado {status or 'desconocido'}")
            last_status = status
        if status in SUCCESS_STATES:
            return
        if status in FAILURE_STATES:
            raise ReleaseError(f"Coolify terminó el despliegue con estado {status}")
        time.sleep(interval)
    raise ReleaseError(f"Coolify no terminó el despliegue en {timeout:.0f} segundos")


def deploy_state(
    client: CoolifyClient,
    state: ReleaseState,
    *,
    timeout: float,
    interval: float,
) -> None:
    client.update_commit(state.commit)
    client.update_image_tag(state.image_tag)
    deployment_uuid = client.deploy()
    wait_for_deployment(client, deployment_uuid, timeout=timeout, interval=interval)


def wait_for_smoke(urls: Sequence[str], *, timeout: float, interval: float) -> None:
    """Espera a que Traefik y los contenedores recién creados estén disponibles."""
    deadline = time.monotonic() + timeout
    while True:
        try:
            run_smoke(urls)
            return
        except Exception:
            if time.monotonic() >= deadline:
                raise
            time.sleep(interval)


def perform_release(
    client: CoolifyClient,
    release_sha: str,
    *,
    urls: Sequence[str] = DEFAULT_URLS,
    timeout: float = 900,
    interval: float = 10,
    smoke_timeout: float = 180,
) -> None:
    if not SHA_PATTERN.fullmatch(release_sha):
        raise ReleaseError("RELEASE_SHA debe ser un commit hexadecimal de 40 caracteres")
    schema_targets = repository_schema_targets()
    target = ReleaseState(release_sha, f"sha-{release_sha}")
    application = client.application()
    values = client.environment()
    validate_environment(values, schema_targets=schema_targets)
    previous = ReleaseState(str(application.get("git_commit_sha", "")), values["GHCR_IMAGE_TAG"])

    if previous.rollback_capable:
        print(f"Comprobando la versión actualmente sana {previous.image_tag}.")
        run_smoke(urls)

    try:
        deploy_state(client, target, timeout=timeout, interval=interval)
        updated_values = dict(values)
        updated_values["GHCR_IMAGE_TAG"] = target.image_tag
        validate_environment(updated_values, target.image_tag, schema_targets)
        wait_for_smoke(urls, timeout=smoke_timeout, interval=interval)
    except Exception as deployment_error:
        if not previous.rollback_capable or previous == target:
            raise ReleaseError(
                f"El despliegue falló y no existe una versión anterior restaurable: {deployment_error}"
            ) from deployment_error
        print(f"El despliegue falló; restaurando {previous.image_tag}.", file=sys.stderr)
        try:
            deploy_state(client, previous, timeout=timeout, interval=interval)
            wait_for_smoke(urls, timeout=smoke_timeout, interval=interval)
        except Exception as rollback_error:
            raise ReleaseError(
                f"Fallaron el despliegue ({deployment_error}) y el rollback ({rollback_error})"
            ) from rollback_error
        raise ReleaseError(
            f"El despliegue falló y Coolify restauró correctamente {previous.image_tag}: "
            f"{deployment_error}"
        ) from deployment_error
    print(f"Versión {target.image_tag} desplegada y validada.")


def required_setting(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ReleaseError(f"Falta la variable {name}")
    return value


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check-repository-schema-targets", action="store_true")
    parser.add_argument("--release-sha", default=os.environ.get("RELEASE_SHA", ""))
    parser.add_argument("--timeout", type=float, default=900)
    parser.add_argument("--poll-interval", type=float, default=10)
    parser.add_argument("--smoke-timeout", type=float, default=180)
    parser.add_argument("--url", action="append", dest="urls")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    arguments = build_parser().parse_args(argv)
    try:
        if arguments.check_repository_schema_targets:
            targets = repository_schema_targets()
            print(
                "Objetivos de esquema configurados: "
                + ", ".join(f"{key}={targets[key]}" for key in SCHEMA_TARGET_KEYS)
            )
            return 0
        client = CoolifyClient(
            required_setting("COOLIFY_URL"),
            required_setting("COOLIFY_TOKEN"),
            required_setting("COOLIFY_APP_UUID"),
        )
        perform_release(
            client,
            arguments.release_sha,
            urls=tuple(arguments.urls or DEFAULT_URLS),
            timeout=arguments.timeout,
            interval=arguments.poll_interval,
            smoke_timeout=arguments.smoke_timeout,
        )
    except (OSError, ReleaseError) as exception:
        print(f"ERROR: {exception}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
