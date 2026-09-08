#!/usr/bin/env python3
"""Coordinador del runtime Bash. Datos y diario JSON sin dependencias de pip."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import copy
import fcntl
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import uuid

from data import (ID, atomic_json, check_hash, child, detect, elf_arch,
                  extract, inspect, read_json, require, validate_component)
from updates import update_candidate, verify_gpg

RUNTIME = Path(__file__).resolve().parent.parent
DEFAULTS = {"LOG_LEVEL": "INFO", "PARALLEL_DOWNLOADS": "4",
            "ROLLBACK_VERSIONS": "2", "ROLLBACK_MAX_BYTES": "2147483648"}

def text(es, en):
    return es if os.environ.get("LANG", "es").lower().startswith("es") else en

def run(argv, **kwargs):
    return subprocess.run(argv, check=True, text=True, **kwargs)

def private_dir(path):
    path = Path(path)
    require(path.is_absolute() and not path.is_symlink(), "unsafe_state_directory")
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    require(path.stat().st_uid == os.getuid() and path.stat().st_mode & 0o022 == 0,
            "unsafe_state_permissions")
    return path.resolve()

def configuration():
    result = dict(DEFAULTS)
    for line in (RUNTIME / "installer.conf").read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        require("=" in line, "invalid_installer_conf")
        key, value = line.split("=", 1)
        require(key in DEFAULTS, "unknown_installer_setting")
        result[key] = value.strip()
    require(result["LOG_LEVEL"] in ("DEBUG", "INFO", "WARN", "ERROR"), "invalid_log_level")
    require(1 <= int(result["PARALLEL_DOWNLOADS"]) <= 8, "invalid_parallelism")
    require(0 <= int(result["ROLLBACK_VERSIONS"]) <= 2, "invalid_retention")
    require(0 <= int(result["ROLLBACK_MAX_BYTES"]) <= 2 * 1024 ** 3, "invalid_cache_limit")
    return result

def load_bundle(root):
    metadata = read_json(root / "config/bundle.json")
    require(metadata.get("schemaVersion") == 1 and ID.fullmatch(metadata.get("id", "")),
            "invalid_bundle")
    components = {}
    for line in (root / "config/packages.conf").read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        require(ID.fullmatch(line) and line not in components, "invalid_package_index")
        components[line] = validate_component(read_json(child(root, f"config/components/{line}.json")))
        require(components[line]["id"] == line, "component_index_mismatch")
    require(len(components) <= 100, "too_many_components")
    return metadata, components

def ordered(components, selection):
    result, active, done = [], set(), set()
    def visit(identifier):
        require(identifier in components, "missing_component_dependency")
        if identifier in done:
            return
        require(identifier not in active, "dependency_cycle")
        active.add(identifier)
        for dependency in components[identifier]["profile"].get("dependencies", []):
            visit(dependency)
        active.remove(identifier)
        done.add(identifier)
        result.append(identifier)
    for identifier in selection or components:
        visit(identifier)
    return result

def compatible(c, machine):
    strategy = c["profile"]["strategy"]
    manager = machine["manager"]
    target = {"deb": "apt", "arch": "pacman"}.get(strategy)
    if strategy == "rpm":
        target = manager if manager in ("dnf", "zypper") else "rpm"
    return (strategy != "manual" and (target is None or target == manager)
            and (not c["profile"].get("linuxTargets")
                 or manager in c["profile"]["linuxTargets"]
                 or "portable" in c["profile"]["linuxTargets"])
            and c["architecture"] in (machine["architecture"], "any", "unknown", "UNKNOWN"))

def confirm(args, message):
    print(message)
    if args.silent:
        return
    require(sys.stdin.isatty(), "confirmation_requires_terminal_or_silent")
    if not args.no_tui:
        frontend = shutil.which("whiptail") or shutil.which("dialog")
        if frontend:
            require(subprocess.call([frontend, "--title", "Batch Linux Installer",
                                     "--yesno", message[:3000], "22", "78"]) == 0, "cancelled")
            return
    require(input(text("Continuar [s/N]: ", "Continue [y/N]: ")).lower() in ("s", "y", "yes"),
            "cancelled")

class Installer:
    def __init__(self, args):
        self.args = args
        self.config = configuration()
        self.machine = detect()
        self.home = Path.home()
        self.state_root = Path(os.environ.get("XDG_STATE_HOME", self.home / ".local/state")) / "batch-linux-installer"
        self.data_root = Path(os.environ.get("XDG_DATA_HOME", self.home / ".local/share")) / "batch-linux-installer"
        self.state_file = self.state_root / "state.json"
        self.journal = self.state_root / "journal.json"
        self.state = read_json(self.state_file) if self.state_file.exists() else {"schemaVersion": 1, "bundles": {}, "apps": {}}
        self.logger = logging.getLogger("batch-linux-installer")
        self.tx = None
        self.lock = None

    def mutable(self):
        private_dir(self.state_root)
        private_dir(self.data_root)
        self.lock = open(self.state_root / "runtime.lock", "a", encoding="utf-8")
        fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.state = read_json(self.state_file) if self.state_file.exists() else self.state
        logs = private_dir(self.state_root / "logs")
        handler = RotatingFileHandler(logs / "install.log", maxBytes=5 * 1024 ** 2, backupCount=4)
        handler.setFormatter(logging.Formatter("%(asctime)s [%(levelname)s] %(message)s"))
        self.logger.addHandler(handler)
        self.logger.setLevel(self.args.log_level or self.config["LOG_LEVEL"])

    def native(self, action, c, payload=None, tx=None):
        request = {"action": action, "component": c, "file": str(payload) if payload else None,
                   "bundle": self.bundle["id"], "uid": os.getuid(), "transaction": tx or self.tx,
                   "scope": self.scope(c), "manager": self.machine["manager"],
                   "runtime": str(RUNTIME), "purge": self.args.purge}
        command = ["python3", "-B", str(RUNTIME / "lib/system.py")]
        if os.geteuid() != 0:
            require(shutil.which("sudo"), "sudo_required")
            command = ["sudo", *(["-n"] if self.args.silent else []), "--", *command]
        result = subprocess.run(command, input=json.dumps(request), capture_output=True, text=True)
        if result.returncode:
            code = result.stderr.strip()
            require(False, code if re.fullmatch(r"[a-z_]{1,100}", code) else "system_operation_failed")
        return json.loads(result.stdout or "{}")

    def scope(self, c):
        if c["profile"]["strategy"] in ("deb", "rpm", "arch"):
            return "system"
        return "system" if (self.args.scope or c["profile"].get("scope", "auto")) == "system" else "user"

    def app_dir(self, c):
        return child(self.data_root, "apps/" + c["appId"])

    def activate(self, app_id, record):
        destination = child(self.data_root, "apps/" + app_id)
        destination.mkdir(parents=True, exist_ok=True, mode=0o700)
        link = destination / "current"
        require(not link.exists() or link.is_symlink(), "unmanaged_current_path")
        tmp = destination / "current.new"
        tmp.unlink(missing_ok=True)
        tmp.symlink_to(Path(record["directory"]).name, target_is_directory=True)
        os.replace(tmp, link)
        launch = self.home / ".local/bin" / ("batch-app-" + app_id)
        launch.parent.mkdir(parents=True, exist_ok=True)
        expected = destination / "current/launch"
        require(not launch.exists() or (launch.is_symlink() and launch.readlink() == expected),
                "unmanaged_launcher")
        if not launch.is_symlink():
            launch.symlink_to(expected)
        desktop = Path(os.environ.get("XDG_DATA_HOME", self.home / ".local/share")) / "applications" / ("batch-" + app_id + ".desktop")
        desktop.parent.mkdir(parents=True, exist_ok=True)
        require(not desktop.is_symlink(), "desktop_symlink")
        title = record["component"]["profile"].get("desktopName") or record["component"].get("name", app_id)
        title = re.sub(r"[\x00-\x1f\x7f]", "", title)[:200]
        escaped = str(launch).replace("\\", "\\\\").replace('"', '\\"').replace(chr(96), "\\" + chr(96)).replace("$", "\\$").replace("%", "%%")
        desktop.write_text(f'[Desktop Entry]\nType=Application\nName={title}\nExec="{escaped}"\nTerminal=false\nCategories=Utility;\n', encoding="utf-8")

    def portable(self, c, payload):
        app_dir = self.app_dir(c)
        app_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        destination = app_dir / c["sha256"]
        if not destination.exists():
            stage = Path(tempfile.mkdtemp(prefix=".stage-", dir=app_dir))
            try:
                target = stage / "payload"
                strategy = c["profile"]["strategy"]
                if strategy == "tarball":
                    target.mkdir()
                    extract(payload, target)
                    entry = child(target, c["profile"]["entrypoint"])
                    require(entry.is_file(), "recipe_entrypoint_missing")
                    if arch := elf_arch(entry):
                        require(arch == self.machine["architecture"], "binary_architecture_mismatch")
                    entry.chmod(entry.stat().st_mode | 0o100)
                    command = [str(destination / "payload" / c["profile"]["entrypoint"])]
                else:
                    shutil.copyfile(payload, target)
                    target.chmod(0o700 if strategy == "appimage" else 0o600)
                    if strategy == "appimage":
                        require(elf_arch(target) == self.machine["architecture"], "binary_architecture_mismatch")
                        command = [str(destination / "payload")]
                    else:
                        require(shutil.which("java"), "java_required")
                        command = ["java", "-jar", str(destination / "payload")]
                (stage / "launch").write_text("#!/usr/bin/env bash\nexec " + shlex.join(command) + ' "$@"\n', encoding="utf-8")
                (stage / "launch").chmod(0o700)
                shutil.copyfile(payload, stage / "original")
                atomic_json(stage / "component.json", c)
                os.replace(stage, destination)
            finally:
                if stage.exists():
                    shutil.rmtree(stage)
        return {"directory": str(destination), "component": c, "scope": "user"}

    def persist_runtime(self):
        target = self.state_root / "runtime"
        # El runtime persistido puede estar ejecutando esta operación.
        if RUNTIME == target:
            return
        stage = self.state_root / "runtime.new"
        if stage.exists(): shutil.rmtree(stage)
        allowed = {"bin", "lib", "plugins", "VERSION", "README.md", "installer.conf",
                   "install.sh", "uninstall.sh", "update.sh", "rollback.sh"}
        def runtime_only(directory, names):
            if Path(directory) == RUNTIME:
                return set(names) - allowed
            return {name for name in names if name == "__pycache__" or name.endswith(".pyc")}
        shutil.copytree(RUNTIME, stage, ignore=runtime_only)
        previous = self.state_root / "runtime.old"
        if previous.exists(): shutil.rmtree(previous)
        if target.exists(): os.replace(target, previous)
        os.replace(stage, target)
        executable = target / "bin/batch-linux-installer"
        executable.chmod(0o700)
        launcher = self.home / ".local/bin/batch-linux-installer"
        launcher.parent.mkdir(parents=True, exist_ok=True)
        require(not launcher.exists() or launcher.is_symlink(), "unmanaged_installer_launcher")
        if launcher.is_symlink(): launcher.unlink()
        launcher.symlink_to(executable)

    def begin(self):
        require(not self.journal.exists(), "unfinished_transaction_run_rollback")
        self.tx = str(uuid.uuid4())
        atomic_json(self.journal, {"transaction": self.tx, "bundle": self.bundle,
                                  "before": self.state, "touched": []})
        self.state = copy.deepcopy(self.state)

    def touch(self, c):
        journal = read_json(self.journal)
        journal["touched"].append(c)
        atomic_json(self.journal, journal)

    def recover(self):
        journal = read_json(self.journal)
        self.bundle = journal["bundle"]
        before = journal["before"]
        system = next((c for c in journal["touched"] if self.scope(c) == "system"
                       or c["profile"].get("systemPackages", {}).get(self.machine["manager"])), None)
        if journal.get("phase") == "committed":
            if system:
                self.native("commit", system, tx=journal["transaction"])
            self.journal.unlink()
            return
        if system:
            self.native("restore", system, tx=journal["transaction"])
        errors = []
        for c in reversed(journal["touched"]):
            try:
                prior = before["apps"].get(c["appId"])
                if prior and prior["current"]["scope"] == "user":
                    self.activate(c["appId"], prior["current"])
                elif self.scope(c) == "user":
                    self.remove_portable(c["appId"])
            except (ValueError, OSError, subprocess.CalledProcessError) as error:
                errors.append(type(error).__name__)
        require(not errors, "rollback_incomplete_retry_rollback")
        self.state = before
        atomic_json(self.state_file, before)
        self.journal.unlink()

    def remove_portable(self, app_id):
        directory = child(self.data_root, "apps/" + app_id)
        launch = self.home / ".local/bin" / ("batch-app-" + app_id)
        if launch.is_symlink() and launch.readlink() == directory / "current/launch":
            launch.unlink()
        desktop = Path(os.environ.get("XDG_DATA_HOME", self.home / ".local/share")) / "applications" / ("batch-" + app_id + ".desktop")
        require(not desktop.is_symlink(), "desktop_symlink")
        desktop.unlink(missing_ok=True)
        # Los payloads quedan hasta confirmar el diario para poder recuperar la desinstalación.
        current = directory / "current"
        if current.is_symlink(): current.unlink()

    def apply(self, c, payload):
        app_id = c["appId"]
        current = self.state["apps"].get(app_id)
        require(not current or current["current"]["scope"] == self.scope(c),
                "scope_migration_requires_uninstall")
        self.touch(c)
        if c["profile"].get("systemPackages", {}).get(self.machine["manager"]):
            self.native("dependencies", c)
        if current and current["current"]["component"]["sha256"] == c["sha256"]:
            current["bundles"] = sorted(set(current["bundles"]) | {self.bundle["id"]})
            if current["current"]["scope"] == "system":
                self.native("install", c, payload)
            return
        if self.scope(c) == "system":
            outcome = self.native("install", c, payload)
            record = {"scope": "system", "component": c, "preexisting": outcome.get("preexisting", False)}
        else:
            record = self.portable(c, payload)
            self.activate(app_id, record)
        history = ([current["current"]] + current.get("history", [])) if current else []
        self.state["apps"][app_id] = {"current": record, "history": history,
                                      "bundles": sorted(set(current["bundles"] if current else []) | {self.bundle["id"]})}

    def prune(self):
        budget, keep = int(self.config["ROLLBACK_MAX_BYTES"]), int(self.config["ROLLBACK_VERSIONS"])
        for app in self.state["apps"].values():
            retained = []
            for old in app["history"]:
                if old["scope"] == "system":
                    if len(retained) < keep: retained.append(old)
                    continue
                directory = Path(old["directory"])
                size = sum(p.stat().st_size for p in directory.rglob("*") if p.is_file() and not p.is_symlink()) if directory.exists() else 0
                if len(retained) < keep and size <= budget:
                    retained.append(old)
                    budget -= size
                elif directory.exists() and directory != Path(app["current"].get("directory", "")):
                    require(directory.resolve().is_relative_to(self.data_root / "apps"), "prune_escape")
                    shutil.rmtree(directory)
            app["history"] = retained
        apps_dir = self.data_root / "apps"
        if apps_dir.exists():
            for directory in apps_dir.iterdir():
                if directory.is_dir() and not directory.is_symlink() and directory.name not in self.state["apps"]:
                    shutil.rmtree(directory)

    def execute(self):
        require(os.name == "posix" and sys.platform.startswith("linux"), "linux_required")
        if self.args.action == "list":
            for identifier, bundle in self.state["bundles"].items(): print(identifier, len(bundle["components"]))
            return
        root = RUNTIME
        if self.args.action == "install":
            self.bundle, self.components = load_bundle(root)
        else:
            identifier = self.args.bundle
            if not identifier and (root / "config/bundle.json").exists():
                identifier = read_json(root / "config/bundle.json")["id"]
            if self.args.action == "rollback" and self.journal.exists():
                journal = read_json(self.journal)
                self.bundle = journal["bundle"]
                if self.args.dry_run:
                    print("rollback", self.bundle["id"]); return
                confirm(self.args, "rollback " + self.bundle["id"])
                self.mutable()
                self.recover()
                return
            require(identifier in self.state["bundles"], "bundle_not_installed_use_list")
            self.bundle = self.state["bundles"][identifier]
            self.components = {key: self.state["apps"][c["appId"]]["current"]["component"]
                               for key, c in self.bundle["components"].items()}
        selection = self.args.components.split(",") if self.args.components else None
        sequence = ordered(self.components, selection)
        if self.args.action in ("uninstall", "rollback"): sequence.reverse()
        if self.args.action == "uninstall" and selection:
            for key, c in self.components.items():
                require(key in sequence or not set(c["profile"].get("dependencies", [])) & set(sequence),
                        "component_still_required")
        summary = "\n".join(f'{c}: {self.components[c].get("name", c)} [{self.components[c]["profile"]["strategy"]}, {self.scope(self.components[c])}]' for c in sequence)
        if self.args.dry_run:
            print(self.machine["name"], self.machine["architecture"])
            print(self.args.action + "\n" + summary)
            for identifier in sequence:
                c = self.components[identifier]
                if not compatible(c, self.machine): print("MANUAL:", identifier)
                elif self.args.action == "install": check_hash(child(root, c["filename"]), c["sha256"])
            return
        require(os.geteuid() != 0, "run_as_user_sudo_requested_when_needed")
        confirm(self.args, self.args.action + "\n" + summary)
        self.mutable()
        require(not self.journal.exists(), "unfinished_transaction_run_rollback")
        payloads, selected = {}, {}
        for identifier in sequence:
            c = self.components[identifier]
            if self.args.action == "install":
                if not compatible(c, self.machine):
                    print("MANUAL:", c.get("name", identifier)); continue
                payload = child(root, c["filename"])
                check_hash(payload, c["sha256"])
                inspect(c["profile"]["strategy"], payload)
                selected[identifier], payloads[identifier] = c, payload
            else: selected[identifier] = c
        if self.args.action == "install":
            # Propaga la indisponibilidad de una dependencia sin impedir las apps independientes.
            while True:
                blocked = [key for key, c in selected.items()
                           if any(d not in selected for d in c["profile"].get("dependencies", []))]
                if not blocked:
                    break
                for key in blocked:
                    print("MANUAL:", key, "dependency_not_installable")
                    del selected[key]
            if not selected:
                print(text("No hay componentes instalables para este equipo.",
                           "No components can be installed on this machine."))
                return
        with tempfile.TemporaryDirectory(prefix="batch-linux-") as temporary:
            work = Path(temporary)
            if self.args.action == "update":
                def prepare(item):
                    key, component = item
                    directory = work / key
                    directory.mkdir()
                    if not component["profile"].get("update"):
                        print("MANUAL:", key, text("Descarga un ZIP nuevo.", "Download a new ZIP."))
                        return key, None
                    return key, update_candidate(component, directory)
                with ThreadPoolExecutor(max_workers=int(self.config["PARALLEL_DOWNLOADS"])) as pool:
                    for identifier, result in pool.map(prepare, selected.items()):
                        if result:
                            selected[identifier], payloads[identifier] = result
                            inspect(selected[identifier]["profile"]["strategy"], result[1])
                selected = {k: v for k, v in selected.items() if k in payloads}
            elif self.args.action == "install":
                for identifier, c in selected.items():
                    verification = c["profile"].get("verification")
                    if verification:
                        directory = work / identifier
                        directory.mkdir()
                        require(c.get("signatureFile"), "publisher_signature_not_bundled")
                        verify_gpg(payloads[identifier], child(root, c["signatureFile"]), verification, directory)
            self.begin()
            try:
                for index, (identifier, c) in enumerate(selected.items(), 1):
                    print(f'[{index}/{len(selected)}] {c.get("name", identifier)}')
                    self.logger.info("%s %s", self.args.action, identifier)
                    if self.args.action in ("install", "update"):
                        self.apply(c, payloads[identifier])
                    elif self.args.action == "uninstall":
                        self.touch(c)
                        app = self.state["apps"].get(c["appId"])
                        if app:
                            remaining = set(app["bundles"]) - {self.bundle["id"]}
                            if app["current"]["scope"] == "system" or c["profile"].get("systemPackages"):
                                self.native("remove", c)
                            if remaining: app["bundles"] = sorted(remaining)
                            else:
                                if app["current"]["scope"] == "user": self.remove_portable(c["appId"])
                                del self.state["apps"][c["appId"]]
                    else:
                        app = self.state["apps"].get(c["appId"])
                        require(app and app["history"], "rollback_version_unavailable")
                        self.touch(c)
                        previous = app["history"].pop(0)
                        if previous["scope"] == "system": self.native("rollback", previous["component"])
                        else:
                            require(Path(previous["directory"]).is_dir(), "rollback_version_unavailable")
                            self.activate(c["appId"], previous)
                        app["history"].insert(0, app["current"])
                        app["current"] = previous
                if self.args.action == "uninstall":
                    kept = {k: c for k, c in self.components.items() if k not in selected}
                    if kept: self.state["bundles"][self.bundle["id"]]["components"] = kept
                    else: self.state["bundles"].pop(self.bundle["id"], None)
                else:
                    bundle = copy.deepcopy(self.bundle)
                    bundle["components"] = {**self.components, **selected}
                    if self.args.action == "install":
                        prior = self.state["bundles"].get(bundle["id"], {}).get("components", {})
                        bundle["components"] = {**prior, **selected}
                    if self.args.action == "rollback":
                        bundle["components"] = {key: self.state["apps"][c["appId"]]["current"]["component"]
                                                for key, c in bundle["components"].items()}
                    self.state["bundles"][bundle["id"]] = bundle
                    self.persist_runtime()
                atomic_json(self.state_file, self.state)
            except BaseException:
                self.logger.error("transaction_failed %s", self.tx)
                self.recover()
                raise
            committed = read_json(self.journal)
            committed["phase"] = "committed"
            atomic_json(self.journal, committed)
            system = next((c for c in committed["touched"] if self.scope(c) == "system"
                           or c["profile"].get("systemPackages", {}).get(self.machine["manager"])), None)
            if system:
                self.native("commit", system)
            self.journal.unlink()
            self.prune()
            atomic_json(self.state_file, self.state)
        atomic_json(self.state_root / "last-result.json",
                    {"bundleId": self.bundle["id"], "action": self.args.action,
                     "status": "completed", "components": list(selected)})
        print(text("Completado. Bundle: ", "Completed. Bundle: ") + self.bundle["id"])
        print(str(self.home / ".local/bin/batch-linux-installer") + " uninstall " + self.bundle["id"])
        if self.args.purge and self.args.action == "uninstall" and not self.state["bundles"]:
            launcher = self.home / ".local/bin/batch-linux-installer"
            if launcher.is_symlink() and launcher.readlink() == self.state_root / "runtime/bin/batch-linux-installer": launcher.unlink()
            logging.shutdown()
            require(self.state_root.name == "batch-linux-installer", "purge_escape")
            shutil.rmtree(self.state_root)

def main():
    parser = argparse.ArgumentParser(description="Batch Linux Installer")
    parser.add_argument("action", choices=["install", "uninstall", "update", "rollback", "list"])
    parser.add_argument("bundle", nargs="?")
    for flag in ("silent", "dry-run", "no-tui", "purge"): parser.add_argument("--" + flag, action="store_true")
    parser.add_argument("--components")
    parser.add_argument("--scope", choices=["user", "system"])
    parser.add_argument("--log-level", choices=["DEBUG", "INFO", "WARN", "ERROR"])
    try: Installer(parser.parse_args()).execute()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        code = str(error) if isinstance(error, ValueError) else type(error).__name__
        print(text("No se pudo completar: ", "Could not complete: ") + code, file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
