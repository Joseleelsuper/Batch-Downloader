"""Pruebas unitarias de la política operativa de Compose."""

from __future__ import annotations

import unittest
from types import SimpleNamespace
from unittest import mock

from scripts import compose_health


def daemon(service: str, health: str = "healthy") -> compose_health.ServiceStatus:
    """Crea un daemon simulado."""
    return compose_health.ServiceStatus(service, "running", health, 0, "Up")


def completed_job(service: str, exit_code: int = 0) -> compose_health.ServiceStatus:
    """Crea un job finalizado simulado."""
    return compose_health.ServiceStatus(service, "exited", "", exit_code, "Exited")


class ComposeHealthTest(unittest.TestCase):
    """Cubre agregación, jobs, parsing, ciclos y paridad."""

    def baseline(self) -> dict[str, compose_health.ServiceStatus]:
        return {service: daemon(service) for service in compose_health.CAPABILITIES["base"]}

    def test_baseline_failure_is_critical(self) -> None:
        statuses = self.baseline()
        statuses["mysql"] = daemon("mysql", "unhealthy")

        state, problems = compose_health.capability_readiness(
            "base", statuses, required=True
        )

        self.assertEqual("failed", state)
        self.assertEqual("mysql", compose_health.root_problem(problems))

    def test_optional_failure_is_degraded(self) -> None:
        statuses = {service: daemon(service) for service in compose_health.CAPABILITIES["semantic"]}
        statuses["postgres"] = daemon("postgres", "unhealthy")

        state, _problems = compose_health.capability_readiness(
            "semantic", statuses, required=False
        )

        self.assertEqual("degraded", state)

    def test_running_without_health_is_starting(self) -> None:
        self.assertEqual(
            "starting",
            compose_health.service_readiness("core-api", {"core-api": daemon("core-api", "")}),
        )

    def test_job_requires_successful_completion(self) -> None:
        self.assertEqual(
            "ready",
            compose_health.service_readiness(
                "minio-init", {"minio-init": completed_job("minio-init")}
            ),
        )
        self.assertEqual(
            "failed",
            compose_health.service_readiness(
                "minio-init", {"minio-init": completed_job("minio-init", 2)}
            ),
        )

    def test_semantic_capability_requires_the_migrator_job(self) -> None:
        statuses = {
            service: daemon(service)
            for service in compose_health.CAPABILITIES["semantic"]
            if service != "semantic-migrator"
        }
        statuses["semantic-migrator"] = completed_job("semantic-migrator", 3)

        state, problems = compose_health.capability_readiness(
            "semantic", statuses, required=False
        )

        self.assertEqual("degraded", state)
        self.assertEqual("semantic-migrator", compose_health.root_problem(problems))

    def test_parse_ps_accepts_json_lines(self) -> None:
        output = (
            '{"Service":"mysql","State":"running","Health":"healthy","ExitCode":0}\n'
            '{"Service":"minio-init","State":"exited","Health":"","ExitCode":0}\n'
        )

        statuses = compose_health.parse_ps_output(output)

        self.assertEqual("healthy", statuses["mysql"].health)
        self.assertEqual(0, statuses["minio-init"].exit_code)

    def test_cycle_detection_returns_the_dependency_chain(self) -> None:
        cycles = compose_health.graph_cycles({"a": {"b"}, "b": {"a"}})

        self.assertEqual([["a", "b", "a"]], cycles)

    def test_blocked_descendants_follow_compose_dependencies(self) -> None:
        services = {
            "mysql": {},
            "scraper-api": {"depends_on": {"mysql": {}}},
            "core-api": {"depends_on": {"scraper-api": {}}},
            "webapp": {"depends_on": {"core-api": {}}},
            "rabbitmq": {},
        }

        blocked = compose_health.blocked_descendants(
            "mysql",
            ["mysql", "scraper-api", "core-api", "webapp", "rabbitmq"],
            services,
        )

        self.assertEqual(["core-api", "scraper-api", "webapp"], blocked)

    def test_start_evaluates_base_after_auxiliary_compose_error(self) -> None:
        arguments = SimpleNamespace(
            deployment="local",
            env_file=".env.example",
            no_build=True,
            require=[],
            timeout=30,
        )
        compose_error = SimpleNamespace(returncode=1, stdout="", stderr="auxiliary failed")
        with (
            mock.patch.object(compose_health, "validate_all", return_value=[]),
            mock.patch.object(
                compose_health,
                "render_compose",
                return_value={"services": {}},
            ),
            mock.patch.object(compose_health, "run_command", return_value=compose_error),
            mock.patch.object(compose_health, "wait_for_required", return_value=0) as wait,
        ):
            result = compose_health.command_start(arguments)

        self.assertEqual(0, result)
        wait.assert_called_once()

    def test_wait_timeout_returns_failure(self) -> None:
        with (
            mock.patch.object(compose_health.time, "monotonic", side_effect=[0.0, 2.0]),
            mock.patch.object(compose_health, "print_report"),
            mock.patch.object(compose_health, "print_diagnostics"),
        ):
            result = compose_health.wait_for_required(
                compose_health.COMPOSE_FILES["local"],
                compose_health.REPOSITORY_ROOT / ".env.example",
                {},
                {"base"},
                1,
            )

        self.assertEqual(1, result)

    def test_redact_includes_secrets_from_rendered_service_environment(self) -> None:
        services = {
            "core-api": {
                "environment": {
                    "CORE_API_ADMIN_PASSWORD": "not-for-logs",
                    "VISIBLE_SETTING": "public",
                }
            }
        }

        output = compose_health.redact(
            "password=not-for-logs VISIBLE_SETTING=public",
            compose_health.REPOSITORY_ROOT / "missing.env",
            services,
        )

        self.assertEqual("password=*** VISIBLE_SETTING=public", output)

    def test_parity_reports_changed_health_topology(self) -> None:
        local = {
            "services": {
                "core-api": {
                    "depends_on": {},
                    "healthcheck": {"test": ["CMD", "true"]},
                    "restart": "unless-stopped",
                },
                **{service: {} for service in compose_health.LOCAL_ONLY_SERVICES},
            }
        }
        ghcr = {
            "services": {
                "core-api": {
                    "depends_on": {},
                    "healthcheck": {"test": ["CMD", "false"]},
                    "restart": "unless-stopped",
                }
            }
        }

        errors = compose_health.validate_parity(local, ghcr)

        self.assertIn("paridad:core-api: configuración funcional distinta", errors)

    def test_private_h2_passwords_are_not_rotated_by_compose(self) -> None:
        """Impide reintroducir secretos externos para los H2 persistentes privados."""
        services = {
            "NOTIFICATION_SERVICE_INBOX_PASSWORD": (
                "services/notification-service/src/main/resources/application.properties"
            ),
            "DOWNLOAD_WORKER_INBOX_PASSWORD": (
                "services/download-worker/src/main/resources/application.properties"
            ),
        }
        compose_sources = [
            path.read_text(encoding="utf-8")
            for path in compose_health.COMPOSE_FILES.values()
        ]
        global_example = (compose_health.REPOSITORY_ROOT / ".env.example").read_text(
            encoding="utf-8"
        )

        for setting, properties_path in services.items():
            for compose_source in compose_sources:
                self.assertNotIn(f"{setting}: ${{{setting}}}", compose_source)
            self.assertNotIn(f"{setting}=", global_example)
            properties = (
                compose_health.REPOSITORY_ROOT / properties_path
            ).read_text(encoding="utf-8")
            self.assertIn(f"spring.datasource.password=${{{setting}:}}", properties)


if __name__ == "__main__":
    unittest.main()
