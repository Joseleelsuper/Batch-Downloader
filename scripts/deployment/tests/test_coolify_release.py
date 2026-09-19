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

    def test_validation_rejects_schema_promotion(self) -> None:
        tag = f"sha-{'a' * 40}"
        values = production_values(tag)
        values["CORE_API_FLYWAY_TARGET"] = "18"

        with self.assertRaises(coolify_release.ReleaseError):
            coolify_release.validate_environment(values)

    def test_success_updates_commit_and_immutable_tag(self) -> None:
        previous = "a" * 40
        release = "b" * 40
        client = FakeCoolifyClient(previous, f"sha-{previous}")
        with mock.patch.object(coolify_release, "run_smoke") as smoke:
            coolify_release.perform_release(client, release, interval=0)

        self.assertEqual(release, client.current_commit)
        self.assertEqual(f"sha-{release}", client.values["GHCR_IMAGE_TAG"])
        self.assertEqual(1, client.deployments)
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
            ),
            self.assertRaises(coolify_release.ReleaseError),
        ):
            coolify_release.perform_release(client, release, interval=0)

        self.assertEqual(previous, client.current_commit)
        self.assertEqual(f"sha-{previous}", client.values["GHCR_IMAGE_TAG"])
        self.assertEqual(2, client.deployments)

    def test_cancelled_by_user_is_terminal_failure(self) -> None:
        client = mock.Mock()
        client.deployment.return_value = {"status": "cancelled-by-user"}

        with self.assertRaises(coolify_release.ReleaseError):
            coolify_release.wait_for_deployment(
                client, "deployment-1", timeout=1, interval=0
            )


if __name__ == "__main__":
    unittest.main()
