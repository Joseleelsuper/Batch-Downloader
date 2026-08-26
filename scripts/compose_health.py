"""Valida, arranca y diagnostica las capacidades de Docker Compose.

La herramienta no intenta sustituir un orquestador: usa los healthchecks de Docker,
respeta la degradación por capacidad y nunca monta el socket del motor en un contenedor.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
COMPOSE_FILES = {
    "local": REPOSITORY_ROOT / "docker-compose.yml",
    "ghcr": REPOSITORY_ROOT / "docker-compose.ghcr.yml",
}

DAEMONS = frozenset(
    {
        "mysql",
        "postgres",
        "rabbitmq",
        "minio",
        "scraper-api",
        "scraper-scheduler",
        "webapp",
        "core-api",
        "semantic-service",
        "semantic-indexer",
        "semantic-model-worker",
        "mailpit",
        "notification-service",
        "download-worker",
        "translation-service",
    }
)
JOBS = frozenset(
    {
        "minio-init",
        "semantic-trainer",
        "scraper-python314t-benchmark",
        "scraper-python314-control",
        "scraper-python314-benchmark-report",
    }
)
LOCAL_ONLY_SERVICES = frozenset(
    {
        "scraper-python314t-benchmark",
        "scraper-python314-control",
        "scraper-python314-benchmark-report",
    }
)
EXPECTED_DEPENDENCIES: dict[str, dict[str, str]] = {
    "mysql": {},
    "postgres": {},
    "rabbitmq": {},
    "minio": {},
    "minio-init": {"minio": "service_healthy"},
    "scraper-api": {"mysql": "service_healthy"},
    "scraper-scheduler": {"scraper-api": "service_healthy"},
    "webapp": {
        "core-api": "service_healthy",
        "minio": "service_healthy",
    },
    "core-api": {
        "mysql": "service_healthy",
        "scraper-api": "service_healthy",
        "minio-init": "service_completed_successfully",
    },
    "semantic-service": {
        "postgres": "service_healthy",
    },
    "semantic-indexer": {
        "semantic-service": "service_healthy",
        "scraper-api": "service_healthy",
    },
    "semantic-model-worker": {
        "semantic-service": "service_healthy",
        "scraper-api": "service_healthy",
    },
    "semantic-trainer": {
        "semantic-service": "service_healthy",
    },
    "scraper-python314t-benchmark": {},
    "scraper-python314-control": {},
    "scraper-python314-benchmark-report": {
        "scraper-python314t-benchmark": "service_completed_successfully",
        "scraper-python314-control": "service_completed_successfully",
    },
    "mailpit": {},
    "notification-service": {"rabbitmq": "service_healthy"},
    "download-worker": {
        "rabbitmq": "service_healthy",
        "minio-init": "service_completed_successfully",
        "core-api": "service_healthy",
        "scraper-api": "service_healthy",
    },
    "translation-service": {},
}
CAPABILITIES: dict[str, tuple[str, ...]] = {
    "base": ("mysql", "scraper-api", "core-api", "webapp"),
    "downloads": (
        "rabbitmq",
        "minio",
        "minio-init",
        "scraper-api",
        "core-api",
        "download-worker",
    ),
    "semantic": (
        "postgres",
        "scraper-api",
        "semantic-service",
        "semantic-indexer",
        "semantic-model-worker",
    ),
    "notifications": ("rabbitmq", "mailpit", "notification-service"),
    "translations": ("translation-service",),
    "background": (
        "scraper-api",
        "semantic-service",
        "scraper-scheduler",
        "semantic-indexer",
        "semantic-model-worker",
    ),
}
SERVICE_PRIORITY = {
    "mysql": 0,
    "postgres": 0,
    "rabbitmq": 0,
    "minio": 0,
    "mailpit": 0,
    "minio-init": 1,
    "scraper-api": 1,
    "semantic-service": 1,
    "translation-service": 1,
    "core-api": 2,
    "webapp": 3,
    "download-worker": 3,
    "notification-service": 3,
    "scraper-scheduler": 3,
    "semantic-indexer": 3,
    "semantic-model-worker": 3,
}
SENSITIVE_NAME = re.compile(
    r"(?i)(PASSWORD|PASS|SECRET|TOKEN|API_KEY|ACCESS_KEY|PRIVATE_KEY|SIGNING_KEY)"
)


class ComposeHealthError(RuntimeError):
    """Representa un fallo controlado de configuración u operación de Compose."""


@dataclass(frozen=True)
class ServiceStatus:
    """Estado normalizado de un servicio persistente o de un job."""

    service: str
    state: str
    health: str
    exit_code: int
    status: str


def compose_command(
    compose_file: Path,
    env_file: Path,
    arguments: Sequence[str],
) -> list[str]:
    """Construye una invocación sin shell para evitar interpolaciones inseguras."""
    return [
        "docker",
        "compose",
        "--env-file",
        str(env_file),
        "-f",
        str(compose_file),
        *arguments,
    ]


def run_command(command: Sequence[str], *, check: bool = True) -> subprocess.CompletedProcess[str]:
    """Ejecuta un comando y conserva salida textual para diagnóstico."""
    try:
        result = subprocess.run(
            list(command),
            cwd=REPOSITORY_ROOT,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError as exception:
        raise ComposeHealthError(f"No se pudo ejecutar {command[0]}: {exception}") from exception
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise ComposeHealthError(detail or f"El comando terminó con código {result.returncode}.")
    return result


def render_compose(compose_file: Path, env_file: Path) -> dict[str, Any]:
    """Renderiza también los perfiles para validar todos los jobs declarados."""
    result = run_command(
        compose_command(
            compose_file,
            env_file,
            ("--profile", "*", "config", "--format", "json"),
        )
    )
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as exception:
        raise ComposeHealthError(f"Compose no devolvió JSON válido: {exception}") from exception


def dependency_graph(services: Mapping[str, Mapping[str, Any]]) -> dict[str, set[str]]:
    """Obtiene el grafo dirigido consumidor-dependencia."""
    return {
        name: set((configuration.get("depends_on") or {}).keys())
        for name, configuration in services.items()
    }


def graph_cycles(graph: Mapping[str, set[str]]) -> list[list[str]]:
    """Localiza ciclos con una búsqueda en profundidad determinista."""
    visiting: set[str] = set()
    visited: set[str] = set()
    stack: list[str] = []
    cycles: list[list[str]] = []

    def visit(node: str) -> None:
        if node in visited:
            return
        if node in visiting:
            start = stack.index(node)
            cycles.append([*stack[start:], node])
            return
        visiting.add(node)
        stack.append(node)
        for dependency in sorted(graph.get(node, set())):
            visit(dependency)
        stack.pop()
        visiting.remove(node)
        visited.add(node)

    for service in sorted(graph):
        visit(service)
    return cycles


def validate_configuration(name: str, configuration: Mapping[str, Any]) -> list[str]:
    """Valida probes, reinicios, jobs y condiciones del grafo de un Compose."""
    errors: list[str] = []
    services: Mapping[str, Mapping[str, Any]] = configuration.get("services") or {}
    service_names = set(services)
    expected = DAEMONS | (JOBS - (LOCAL_ONLY_SERVICES if name == "ghcr" else frozenset()))
    missing = expected - service_names
    unexpected = service_names - expected
    if missing:
        errors.append(f"{name}: faltan servicios: {', '.join(sorted(missing))}")
    if unexpected:
        errors.append(f"{name}: servicios sin clasificar: {', '.join(sorted(unexpected))}")

    for service in sorted(DAEMONS & service_names):
        item = services[service]
        healthcheck = item.get("healthcheck") or {}
        if not healthcheck:
            errors.append(f"{name}:{service}: daemon sin healthcheck explícito")
        elif not all(
            healthcheck.get(field) is not None
            for field in ("test", "interval", "timeout", "retries", "start_period")
        ):
            errors.append(f"{name}:{service}: healthcheck sin ventana explícita completa")
        if item.get("restart") != "unless-stopped":
            errors.append(f"{name}:{service}: restart debe ser unless-stopped")

    for service in sorted(JOBS & service_names):
        item = services[service]
        if item.get("restart") != "no":
            errors.append(f"{name}:{service}: job con restart distinto de no")
        if item.get("healthcheck"):
            errors.append(f"{name}:{service}: un job no debe declarar healthcheck")

    for consumer, item in services.items():
        actual_dependencies = {
            dependency: rule.get("condition")
            for dependency, rule in (item.get("depends_on") or {}).items()
        }
        expected_dependencies = EXPECTED_DEPENDENCIES.get(consumer, {})
        if actual_dependencies != expected_dependencies:
            errors.append(f"{name}:{consumer}: grafo de dependencias distinto del esperado")
        for dependency, rule in (item.get("depends_on") or {}).items():
            condition = rule.get("condition")
            if condition == "service_started":
                errors.append(f"{name}:{consumer}->{dependency}: service_started no está permitido")
            if condition == "service_healthy" and not services.get(dependency, {}).get(
                "healthcheck"
            ):
                errors.append(
                    f"{name}:{consumer}->{dependency}: service_healthy apunta a un servicio sin probe"
                )
            if condition == "service_completed_successfully" and dependency not in JOBS:
                errors.append(
                    f"{name}:{consumer}->{dependency}: finalización aplicada a un daemon"
                )

    for cycle in graph_cycles(dependency_graph(services)):
        errors.append(f"{name}: ciclo de dependencias: {' -> '.join(cycle)}")
    return errors


def comparable_service(configuration: Mapping[str, Any]) -> dict[str, Any]:
    """Normaliza solo las diferencias de distribución admitidas entre despliegues."""
    ignored = {"build", "image", "pull_policy"}
    return {key: value for key, value in configuration.items() if key not in ignored}


def validate_parity(local: Mapping[str, Any], ghcr: Mapping[str, Any]) -> list[str]:
    """Comprueba que solo difieran build/image y los benchmarks locales permitidos."""
    errors: list[str] = []
    local_services: Mapping[str, Mapping[str, Any]] = local.get("services") or {}
    ghcr_services: Mapping[str, Mapping[str, Any]] = ghcr.get("services") or {}
    if set(local_services) - set(ghcr_services) != LOCAL_ONLY_SERVICES:
        errors.append("paridad: la diferencia de servicios no coincide con los benchmarks locales")
    extra_ghcr = set(ghcr_services) - set(local_services)
    if extra_ghcr:
        errors.append(f"paridad: GHCR tiene servicios extra: {', '.join(sorted(extra_ghcr))}")
    for service in sorted(set(local_services) & set(ghcr_services)):
        if comparable_service(local_services[service]) != comparable_service(ghcr_services[service]):
            errors.append(f"paridad:{service}: configuración funcional distinta")
    return errors


def validate_all(env_file: Path) -> list[str]:
    """Valida estructura y paridad de los dos despliegues."""
    rendered = {
        name: render_compose(compose_file, env_file)
        for name, compose_file in COMPOSE_FILES.items()
    }
    errors = [
        error
        for name, configuration in rendered.items()
        for error in validate_configuration(name, configuration)
    ]
    errors.extend(validate_parity(rendered["local"], rendered["ghcr"]))
    return errors


def parse_ps_output(output: str) -> dict[str, ServiceStatus]:
    """Acepta tanto JSON por líneas como un array producido por distintas versiones."""
    stripped = output.strip()
    if not stripped:
        return {}
    try:
        value = json.loads(stripped)
        rows = value if isinstance(value, list) else [value]
    except json.JSONDecodeError:
        rows = [json.loads(line) for line in stripped.splitlines() if line.strip()]
    statuses: dict[str, ServiceStatus] = {}
    for row in rows:
        service = str(row.get("Service") or row.get("service") or "")
        if not service:
            continue
        statuses[service] = ServiceStatus(
            service=service,
            state=str(row.get("State") or row.get("state") or "").lower(),
            health=str(row.get("Health") or row.get("health") or "").lower(),
            exit_code=int(row.get("ExitCode") or row.get("exitCode") or 0),
            status=str(row.get("Status") or row.get("status") or ""),
        )
    return statuses


def runtime_status(compose_file: Path, env_file: Path) -> dict[str, ServiceStatus]:
    """Consulta todos los contenedores del proyecto sin modificar su estado."""
    result = run_command(
        compose_command(compose_file, env_file, ("ps", "--all", "--format", "json"))
    )
    return parse_ps_output(result.stdout)


def service_readiness(service: str, statuses: Mapping[str, ServiceStatus]) -> str:
    """Traduce el estado de Docker a ready, starting, failed o stopped."""
    status = statuses.get(service)
    if status is None:
        return "stopped"
    if service in JOBS:
        if status.state == "exited":
            return "ready" if status.exit_code == 0 else "failed"
        return "starting" if status.state in {"created", "restarting", "running"} else "failed"
    if status.state == "running":
        if status.health == "healthy":
            return "ready"
        if status.health == "unhealthy":
            return "failed"
        return "starting"
    if status.state in {"created", "restarting"}:
        return "starting"
    return "failed" if status.state else "stopped"


def capability_readiness(
    capability: str,
    statuses: Mapping[str, ServiceStatus],
    *,
    required: bool,
) -> tuple[str, list[str]]:
    """Agrega una capacidad y devuelve los servicios que impiden declararla lista."""
    states = {
        service: service_readiness(service, statuses)
        for service in CAPABILITIES[capability]
    }
    problems = [service for service, state in states.items() if state != "ready"]
    if not problems:
        return "ready", []
    if any(states[service] in {"failed", "stopped"} for service in problems):
        return ("failed" if required else "degraded"), problems
    return "starting", problems


def root_problem(services: Iterable[str]) -> str | None:
    """Escoge el fallo de menor nivel para evitar culpar primero al consumidor."""
    values = list(services)
    if not values:
        return None
    return min(values, key=lambda service: (SERVICE_PRIORITY.get(service, 99), service))


def blocked_descendants(
    root: str | None,
    problems: Iterable[str],
    services: Mapping[str, Mapping[str, Any]],
) -> list[str]:
    """Devuelve los servicios problemáticos que dependen transitivamente de la raíz."""
    if root is None:
        return []
    graph = dependency_graph(services)

    def depends_on(service: str, dependency: str, visited: set[str]) -> bool:
        if service in visited:
            return False
        visited.add(service)
        direct = graph.get(service, set())
        return dependency in direct or any(
            depends_on(candidate, dependency, visited) for candidate in direct
        )

    return sorted(
        service
        for service in set(problems) - {root}
        if depends_on(service, root, set())
    )


def print_report(
    statuses: Mapping[str, ServiceStatus],
    required: set[str],
    services: Mapping[str, Mapping[str, Any]],
) -> None:
    """Muestra capacidades, primer fallo raíz y descendientes bloqueados."""
    print("CAPACIDAD      ESTADO      CAUSA RAÍZ              BLOQUEADOS")
    for capability in CAPABILITIES:
        state, problems = capability_readiness(
            capability,
            statuses,
            required=capability in required,
        )
        root = root_problem(problems)
        blocked = ",".join(blocked_descendants(root, problems, services)) or "-"
        print(f"{capability:<14} {state:<11} {(root or '-'):<23} {blocked}")


def sensitive_values(
    env_file: Path,
    services: Mapping[str, Mapping[str, Any]] | None = None,
) -> list[str]:
    """Obtiene secretos configurados para censurarlos de cualquier diagnóstico."""
    values: list[str] = []
    if env_file.exists():
        for line in env_file.read_text(encoding="utf-8").splitlines():
            if not line or line.lstrip().startswith("#") or "=" not in line:
                continue
            name, value = line.split("=", 1)
            value = value.strip().strip("'\"")
            if SENSITIVE_NAME.search(name) and value:
                values.append(value)
    for service in (services or {}).values():
        environment = service.get("environment") or {}
        if not isinstance(environment, Mapping):
            continue
        values.extend(
            str(value)
            for name, value in environment.items()
            if SENSITIVE_NAME.search(str(name)) and value
        )
    return sorted(set(values), key=len, reverse=True)


def redact(
    output: str,
    env_file: Path,
    services: Mapping[str, Mapping[str, Any]] | None = None,
) -> str:
    """Censura secretos conocidos y cabeceras de autorización."""
    redacted = output
    for value in sensitive_values(env_file, services):
        redacted = redacted.replace(value, "***")
    return re.sub(r"(?i)(authorization:\s*(?:bearer|basic)\s+)\S+", r"\1***", redacted)


def print_diagnostics(
    compose_file: Path,
    env_file: Path,
    statuses: Mapping[str, ServiceStatus],
    problems: Iterable[str],
    services: Mapping[str, Mapping[str, Any]],
) -> None:
    """Conserva el entorno y muestra estado y logs acotados del fallo de menor nivel."""
    root = root_problem(problems)
    print("\nEl entorno se conserva para diagnóstico.", file=sys.stderr)
    ps = run_command(
        compose_command(compose_file, env_file, ("ps", "--all")),
        check=False,
    )
    print(redact(ps.stdout or ps.stderr, env_file, services), file=sys.stderr)
    if root and root in statuses:
        logs = run_command(
            compose_command(
                compose_file,
                env_file,
                ("logs", "--no-color", "--tail", "80", root),
            ),
            check=False,
        )
        print(f"Logs de {root}:", file=sys.stderr)
        print(redact(logs.stdout + logs.stderr, env_file, services), file=sys.stderr)


def wait_for_required(
    compose_file: Path,
    env_file: Path,
    services: Mapping[str, Mapping[str, Any]],
    required: set[str],
    timeout_seconds: int,
) -> int:
    """Espera las capacidades requeridas y falla pronto ante un probe terminal."""
    deadline = time.monotonic() + timeout_seconds
    last_statuses: dict[str, ServiceStatus] = {}
    while time.monotonic() < deadline:
        last_statuses = runtime_status(compose_file, env_file)
        evaluations = {
            capability: capability_readiness(
                capability,
                last_statuses,
                required=True,
            )
            for capability in required
        }
        failed = [
            service
            for state, problems in evaluations.values()
            if state == "failed"
            for service in problems
            if service_readiness(service, last_statuses) == "failed"
        ]
        if failed:
            print_report(last_statuses, required, services)
            print_diagnostics(compose_file, env_file, last_statuses, failed, services)
            return 1
        if all(state == "ready" for state, _problems in evaluations.values()):
            print_report(last_statuses, required, services)
            return 0
        time.sleep(2)

    problems = [
        service
        for capability in required
        for service in capability_readiness(
            capability,
            last_statuses,
            required=True,
        )[1]
    ]
    print_report(last_statuses, required, services)
    print(f"Timeout de arranque tras {timeout_seconds} segundos.", file=sys.stderr)
    print_diagnostics(compose_file, env_file, last_statuses, problems, services)
    return 1


def deployment_file(name: str) -> Path:
    """Resuelve el Compose de un despliegue conocido."""
    return COMPOSE_FILES[name]


def resolve_env_file(value: str) -> Path:
    """Resuelve un archivo de entorno respecto de la raíz del repositorio."""
    path = Path(value)
    return path if path.is_absolute() else REPOSITORY_ROOT / path


def required_capabilities(values: Sequence[str]) -> set[str]:
    """La capacidad base siempre es obligatoria; el resto es seleccionable."""
    return {"base", *values}


def command_validate(arguments: argparse.Namespace) -> int:
    """Implementa el subcomando validate."""
    errors = validate_all(resolve_env_file(arguments.env_file))
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print("Los Compose local y GHCR tienen una topología de salud válida y equivalente.")
    return 0


def command_status(arguments: argparse.Namespace) -> int:
    """Implementa el subcomando status sin modificar contenedores."""
    compose_file = deployment_file(arguments.deployment)
    env_file = resolve_env_file(arguments.env_file)
    required = required_capabilities(arguments.require)
    configuration = render_compose(compose_file, env_file)
    statuses = runtime_status(compose_file, env_file)
    print_report(statuses, required, configuration.get("services") or {})
    return 0 if all(
        capability_readiness(capability, statuses, required=True)[0] == "ready"
        for capability in required
    ) else 1


def command_start(arguments: argparse.Namespace) -> int:
    """Implementa un arranque validado que nunca desmonta el entorno al fallar."""
    env_file = resolve_env_file(arguments.env_file)
    errors = validate_all(env_file)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    compose_file = deployment_file(arguments.deployment)
    configuration = render_compose(compose_file, env_file)
    up_arguments = ["up", "-d"]
    if arguments.deployment == "local" and not arguments.no_build:
        up_arguments.append("--build")
    result = run_command(
        compose_command(compose_file, env_file, tuple(up_arguments)),
        check=False,
    )
    if result.returncode != 0:
        print(
            "ADVERTENCIA: Compose informó un fallo durante el arranque; "
            "se evaluarán las capacidades requeridas antes de decidir el resultado.",
            file=sys.stderr,
        )
        print(
            redact(
                result.stdout + result.stderr,
                env_file,
                configuration.get("services") or {},
            ),
            file=sys.stderr,
        )
    return wait_for_required(
        compose_file,
        env_file,
        configuration.get("services") or {},
        required_capabilities(arguments.require),
        arguments.timeout,
    )


def build_parser() -> argparse.ArgumentParser:
    """Declara la interfaz de línea de comandos."""
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate = subparsers.add_parser("validate", help="Valida ambos Compose sin arrancarlos.")
    validate.add_argument("--env-file", default=".env.example")
    validate.set_defaults(handler=command_validate)

    for command, handler in (("status", command_status), ("start", command_start)):
        subparser = subparsers.add_parser(command)
        subparser.add_argument("--deployment", choices=tuple(COMPOSE_FILES), default="local")
        subparser.add_argument("--env-file", default=".env")
        subparser.add_argument(
            "--require",
            action="append",
            choices=tuple(capability for capability in CAPABILITIES if capability != "base"),
            default=[],
            help="Convierte una capacidad degradable en requisito del comando.",
        )
        subparser.set_defaults(handler=handler)
    start = subparsers.choices["start"]
    start.add_argument("--timeout", type=int, default=600)
    start.add_argument("--no-build", action="store_true")
    return parser


def main() -> int:
    """Ejecuta la operación solicitada y traduce errores controlados a código uno."""
    parser = build_parser()
    arguments = parser.parse_args()
    try:
        return int(arguments.handler(arguments))
    except ComposeHealthError as exception:
        print(f"ERROR: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
