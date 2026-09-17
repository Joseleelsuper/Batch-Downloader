"""Recuperación del registro root ante interrupciones entre paquete y estado."""

import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid

REPO = Path(__file__).resolve().parents[3]
RUNTIME = REPO / "services/download-worker/src/main/resources/linux-installer"
sys.path.insert(0, str(RUNTIME / "lib"))
import system  # noqa: E402


class SystemJournalTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="bd-system-journal-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / "registry"
        self.transactions = self.root / "transactions"
        self.transactions.mkdir(parents=True)
        self.bundle = str(uuid.uuid4())
        self.transaction = str(uuid.uuid4())
        self.app = str(uuid.uuid4())
        self.package = "bd-interrupted-fixture"
        self.owner = f"2000:{self.bundle}:{self.app}"
        self.component = {
            "schemaVersion": 1,
            "id": self.app,
            "appId": self.app,
            "name": "Interrupted fixture",
            "version": "1",
            "filename": "fixture.deb",
            "sha256": "a" * 64,
            "architecture": "any",
            "profile": {"schemaVersion": 1, "strategy": "deb", "scope": "system"},
        }
        self.request = {
            "action": "restore",
            "manager": "apt",
            "uid": 2000,
            "bundle": self.bundle,
            "transaction": self.transaction,
            "component": self.component,
        }
        (self.root / "state.json").write_text(json.dumps({"records": {}}))

    def write_intent(self):
        record = {
            "manager": "apt",
            "name": self.package,
            "version": "1",
            "preexisting": False,
            "owners": [self.owner],
            "history": [],
            "file": "/var/lib/batch-linux-installer/cache/fixture.deb",
        }
        journal = self.transactions / f"2000-{self.transaction}.json"
        journal.write_text(
            json.dumps({"records": {}, "intents": {"apt:" + self.package: record}})
        )
        return journal

    def invoke_restore(self, installed_version):
        def manager_call(_manager, action, _value, check=True):
            return installed_version if action == "query" else None

        original_require = system.require

        def require(value, code):
            if code != "unsafe_system_registry":
                original_require(value, code)

        with (
            patch.object(system, "ROOT", self.root),
            patch.object(system, "detect", return_value={"manager": "apt"}),
            patch.object(system, "manager_call", side_effect=manager_call),
            patch.object(system, "require", side_effect=require),
            patch.object(system.os, "geteuid", return_value=0),
            patch.object(system.sys, "stdin", io.StringIO(json.dumps(self.request))),
            patch.dict(os.environ, {"SUDO_UID": "2000"}),
            patch.object(system, "safe_remove") as remove,
        ):
            system.main()
            return remove

    def test_restore_removes_package_installed_before_state_was_persisted(self):
        journal = self.write_intent()

        remove = self.invoke_restore("1")

        remove.assert_called_once_with("apt", self.package)
        self.assertFalse(journal.exists())
        self.assertEqual(
            json.loads((self.root / "state.json").read_text()), {"records": {}}
        )

    def test_restore_preserves_external_change_and_keeps_journal(self):
        journal = self.write_intent()

        with self.assertRaisesRegex(ValueError, "package_changed_externally"):
            self.invoke_restore("2")

        self.assertTrue(journal.exists())


if __name__ == "__main__":
    unittest.main()
