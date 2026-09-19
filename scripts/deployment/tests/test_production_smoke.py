"""Pruebas unitarias del smoke test externo."""

from __future__ import annotations

import unittest
from unittest import mock

from scripts.deployment import production_smoke


class ProductionSmokeTest(unittest.TestCase):
    def test_rejects_non_object_health_payload(self) -> None:
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = b"[]"
        with (
            mock.patch.object(production_smoke, "urlopen", return_value=response),
            self.assertRaises(production_smoke.SmokeError),
        ):
            production_smoke.check_url("https://example.test/healthz", 15)

    def test_rejects_plain_http(self) -> None:
        with self.assertRaises(production_smoke.SmokeError):
            production_smoke.run_smoke(("http://example.test/healthz",))

    def test_checks_each_hostname_certificate_once(self) -> None:
        urls = (
            "https://example.test/healthz",
            "https://example.test/api/v1/locales/es",
        )
        with (
            mock.patch.object(
                production_smoke,
                "certificate_days_remaining",
                return_value=80,
            ) as certificate,
            mock.patch.object(production_smoke, "check_url") as check,
        ):
            production_smoke.run_smoke(urls)

        certificate.assert_called_once_with("example.test", 443, 15)
        self.assertEqual(2, check.call_count)

    def test_rejects_certificate_near_expiry(self) -> None:
        with (
            mock.patch.object(
                production_smoke,
                "certificate_days_remaining",
                return_value=3,
            ),
            mock.patch.object(production_smoke, "check_url") as check,
            self.assertRaises(production_smoke.SmokeError),
        ):
            production_smoke.run_smoke(("https://example.test/healthz",))

        check.assert_not_called()


if __name__ == "__main__":
    unittest.main()
