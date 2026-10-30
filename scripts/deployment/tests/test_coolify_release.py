"""Pruebas del despliegue serializado y su rollback."""

from __future__ import annotations

import unittest
from typing import Any
from unittest import mock

from scripts.deployment import coolify_release


def production_values(tag: str) -> dict[str, str]:
    values = {key: "configured" for key in coolify_release.REQUIRED_VALUES}
    values.update(coolify_release.FIXED_PRODUCTION_VALUES)
    values["GHCR_IMAGE_TAG"] = tag
    return values


class FakeCoolifyClient:
    def __init__(self, commit: str, tag: str):
        self.current_commit = commit
        self.values = production_values(tag)
        self.actions: list[tuple[str, str]] = []
        self.deployments = 0

    def application(self) -> dict[str, str]:
        return {"git_commit_sha": self.current_commit}

    def environment(self) -> dict[str, str]:
        return dict(self.values)

    def update_commit(self, commit: str) -> None:
        self.current_commit = commit
        self.actions.append(("commit", commit))

    def update_image_tag(self, tag: str) -> None:
        self.values["GHCR_IMAGE_TAG"] = tag
        self.actions.append(("tag", tag))

    def deploy(self) -> str:
        self.deployments += 1
        return f"deployment-{self.deployments}"

    def deployment(self, _deployment_uuid: str) -> dict[str, Any]:
        return {"status": "finished"}


class CoolifyReleaseTest(unittest.TestCase):
    def test_empty_compose_settings_stop_before_deployment(self) -> None:
        for key in ("SCRAPER_ALLOWED_DOWNLOAD_SCHEMES", "SEMANTIC_DATABASE_PORT",
                    "TRANSLATION_SERVICE_SERVER_PORT"):
            with self.subTest(key=key):
                client = FakeCoolifyClient("a" * 40, f"sha-{'a' * 40}")
                client.values[key] = ""
                with mock.patch.object(coolify_release, "run_smoke") as smoke:
                    with self.assertRaisesRegex(coolify_release.ReleaseError, key):
                        coolify_release.perform_release(client, "b" * 40)
                self.assertEqual([], client.actions)
                self.assertEqual(0, client.deployments)
                smoke.assert_not_called()

    def test_environment_ignores_preview_values(self) -> None:
        client = coolify_release.CoolifyClient(
            "https://deploy.example.test", "token", "application-uuid"
        )
        with mock.patch.object(
            client,
            "request",
            return_value=[
                {
                    "key": "APP_PUBLIC_BASE_URL",
                    "value": "https://batchdownloader.dev",
                    "is_preview": False,
                },
                {"key": "APP_PUBLIC_BASE_URL", "value": None, "is_preview": True},
            ],
        ):
            values = client.environment()

        self.assertEqual(
            {"APP_PUBLIC_BASE_URL": "https://batchdownloader.dev"}, values
        )

    def test_environment_accepts_configured_show_once_secret(self) -> None:
        client = coolify_release.CoolifyClient(
            "https://deploy.example.test", "token", "application-uuid"
        )
        with mock.patch.object(
            client,
            "request",
            return_value=[
                {
                    "key": "CORE_API_ADMIN_PASSWORD",
                    "value": "",
                    "real_value": "",
                    "is_preview": False,
                    "is_shown_once": True,
                }
            ],
        ):
            values = client.environment()

        self.assertEqual("configured-secret", values["CORE_API_ADMIN_PASSWORD"])

    def test_environment_prefers_public_value_over_shell_quoted_real_value(self) -> None:
        client = coolify_release.CoolifyClient(
            "https://deploy.example.test", "token", "application-uuid"
        )
        with mock.patch.object(
            client,
            "request",
            return_value=[
                {
                    "key": "APP_PUBLIC_BASE_URL",
                    "value": "https://batchdownloader.dev",
                    "real_value": "'https://batchdownloader.dev'",
                    "is_preview": False,
                    "is_shown_once": False,
                }
            ],
        ):
            values = client.environment()

        self.assertEqual("https://batchdownloader.dev", values["APP_PUBLIC_BASE_URL"])

    def test_client_uses_documented_commit_and_environment_endpoints(self) -> None:
        client = coolify_release.CoolifyClient(
            "https://deploy.example.test", "token", "application-uuid"
        )
        tag = f"sha-{'a' * 40}"
        with mock.patch.object(client, "request") as request:
            client.update_commit("a" * 40)
            client.update_image_tag(tag)

        self.assertEqual(
            [
                mock.call(
                    "PATCH",
                    "/applications/application-uuid",
                    {"git_commit_sha": "a" * 40},
                ),
                mock.call(
                    "PATCH",
                    "/applications/application-uuid/envs",
                    {"key": "GHCR_IMAGE_TAG", "value": tag, "is_preview": False},
                ),
            ],
            request.call_args_list,
        )

    def test_validation_does_not_require_a_manual_migration_target(self) -> None:
        values = production_values(f"sha-{'a' * 40}")
        self.assertNotIn("CORE_API_FLYWAY_TARGET", values)
        coolify_release.validate_environment(values)
        values["CORE_API_FLYWAY_TARGET"] = "16"
        coolify_release.validate_environment(values)

    def test_oracle_profile_does_not_require_retired_limits(self) -> None:
        values = production_values(f"sha-{'a' * 40}")
        for key in (
            "DOWNLOAD_WORKER_MAX_TOTAL_SIZE",
            "DOWNLOAD_WORKER_CONCURRENCY",
            "DOWNLOAD_WORKER_JOB_CONCURRENCY",
            "DOWNLOAD_WORKER_LARGE_JOB_THRESHOLD",
        ):
            values.pop(key, None)

        coolify_release.validate_environment(values)

    def test_compose_settings_accept_zero_as_a_nonempty_value(self) -> None:
        values = production_values(f"sha-{'a' * 40}")
        values["DOWNLOAD_WORKER_ZIP_LEVEL"] = "0"
        coolify_release.validate_environment(values)

    def test_oracle_profile_rejects_storage_budget_or_disk_margin_drift(self) -> None:
        for key in ("MINIO_ZIP_QUOTA", "DOWNLOAD_WORKER_MIN_FREE_SPACE"):
            with self.subTest(key=key):
                values = production_values(f"sha-{'a' * 40}")
                values[key] = "20GB"

                with self.assertRaisesRegex(coolify_release.ReleaseError, key):
                    coolify_release.validate_environment(values)

    def test_release_drift_stops_before_coolify_mutation(self) -> None:
        previous = "a" * 40
        release = "b" * 40
        client = FakeCoolifyClient(previous, f"sha-{previous}")
        client.values["MINIO_ZIP_QUOTA"] = "20GB"

        with self.assertRaisesRegex(coolify_release.ReleaseError, "MINIO_ZIP_QUOTA"):
            coolify_release.perform_release(client, release)

        self.assertEqual([], client.actions)
        self.assertEqual(0, client.deployments)

    def test_success_updates_commit_and_immutable_tag(self) -> None:
        previous = "a" * 40
        release = "b" * 40
        client = FakeCoolifyClient(previous, f"sha-{previous}")
        with mock.patch.object(coolify_release, "run_smoke") as smoke:
            coolify_release.perform_release(client, release, interval=0)

        self.assertEqual(release, client.current_commit)
        self.assertEqual(f"sha-{release}", client.values["GHCR_IMAGE_TAG"])
        self.assertEqual(1, client.deployments)
        self.assertEqual(
            [
                mock.call((
                    "https://batchdownloader.dev/healthz",
                    "https://batchdownloader.dev/api/v1/locales/es",
                )),
                mock.call(coolify_release.DEFAULT_URLS),
            ],
            smoke.call_args_list,
        )

    def test_smoke_waits_for_new_containers_to_be_ready(self) -> None:
        with mock.patch.object(
            coolify_release,
            "run_smoke",
            side_effect=[RuntimeError("503"), None],
        ) as smoke:
            coolify_release.wait_for_smoke((), timeout=1, interval=0)

        self.assertEqual(2, smoke.call_count)

    def test_failed_smoke_restores_previous_commit_and_tag(self) -> None:
        previous = "a" * 40
        release = "b" * 40
        client = FakeCoolifyClient(previous, f"sha-{previous}")
        with (
            mock.patch.object(
                coolify_release,
                "run_smoke",
                side_effect=[None, RuntimeError("new version failed"), None],
            ) as smoke,
            self.assertRaises(coolify_release.ReleaseError),
        ):
            coolify_release.perform_release(
                client, release, interval=0, smoke_timeout=0
            )

        self.assertEqual(previous, client.current_commit)
        self.assertEqual(f"sha-{previous}", client.values["GHCR_IMAGE_TAG"])
        self.assertEqual(2, client.deployments)
        self.assertEqual(smoke.call_args_list[0], smoke.call_args_list[2])

    def test_cancelled_by_user_is_terminal_failure(self) -> None:
        client = mock.Mock()
        client.deployment.return_value = {"status": "cancelled-by-user"}

        with self.assertRaises(coolify_release.ReleaseError):
            coolify_release.wait_for_deployment(
                client, "deployment-1", timeout=1, interval=0
            )


if __name__ == "__main__":
    unittest.main()
