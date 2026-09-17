#!/usr/bin/env python3
"""Operaciones privilegiadas puntuales y registro compartido de propiedad."""
from __future__ import annotations
import copy
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
from data import (ARCHES, ID, PACKAGE, atomic_json, check_hash, child, detect,
                  inspect, read_json, require, validate_component)

from portable import prepare_portable

ROOT = Path("/var/lib/batch-linux-installer")
SYSTEM_ROOT = Path("/opt/batch-linux-installer")
RUNTIME = Path(__file__).resolve().parent.parent

def manager_call(manager, action, value, check=True):
    result = subprocess.run(["bash", str(RUNTIME / f"plugins/package-managers/{manager}.sh"),
                             action, value], text=True, capture_output=True, check=check)
    return result.stdout.strip() if result.returncode == 0 else None

def safe_remove(manager, package):
    # Los gestores pueden proponer quitar dependientes; abortar antes de ejecutar.
    if manager == "apt":
        result = subprocess.run(["apt-get", "-s", "remove", "--", package],
                                check=True, text=True, capture_output=True)
        removed = [line.split()[1].split(":")[0] for line in result.stdout.splitlines()
                   if line.startswith(("Remv ", "Purg "))]
        require(set(removed) <= {package.split(":")[0]}, "package_required_by_other_software")
    elif manager in ("dnf", "zypper"):
        subprocess.run(["rpm", "-e", "--test", "--", package], check=True, capture_output=True)
    manager_call(manager, "remove", package)

def native_record(manager, package, payload, owner, state, dependency=False):
    key = manager + ":" + package
    current = manager_call(manager, "query", package, check=False)
    record = state["records"].get(key)
    if record:
        require(record["version"] == current, "package_changed_externally")
        record["owners"] = sorted(set(record["owners"]) | {owner})
        if dependency or record["preexisting"]:
            return record
    elif current:
        record = {"manager": manager, "name": package, "version": current,
                  "preexisting": True, "owners": [owner], "history": [], "file": None}
        state["records"][key] = record
        return record
    else:
        record = {"manager": manager, "name": package, "version": None,
                  "preexisting": False, "owners": [owner], "history": [], "file": None}
        state["records"][key] = record
    if dependency:
        try:
            manager_call(manager, "ensure", package)
        finally:
            record["version"] = manager_call(manager, "query", package, check=False)
    else:
        desired = manager_call(manager, "version", str(payload))
        if current == desired: return record
        if record.get("file"):
            record["history"].insert(0, {"version": current, "file": record["file"]})
        try:
            manager_call(manager, "install", str(payload))
        finally:
            record["version"] = manager_call(manager, "query", package, check=False)
        record["file"] = str(payload)
    record["version"] = manager_call(manager, "query", package)
    return record

def system_portable(c, payload, owner, state):
    key = "portable:" + c["appId"]
    record = state["records"].get(key)
    base = child(SYSTEM_ROOT, c["appId"])
    base.mkdir(parents=True, exist_ok=True, mode=0o755)
    destination = base / c["sha256"]
    if not destination.exists():
        stage = Path(tempfile.mkdtemp(prefix=".stage-", dir=base))
        try:
            prepare_portable(c, payload, stage, destination, 0o755)
            stage.chmod(0o755)
            os.replace(stage, destination)
        finally:
            if stage.exists(): shutil.rmtree(stage)
    history = record["history"] if record else []
    if record and record.get("directory") != str(destination):
        history = [{"directory": record["directory"], "version": record["version"]}, *history]
    record = {"manager": "portable", "name": c["appId"], "version": c["sha256"],
              "preexisting": False, "directory": str(destination), "history": history,
              "owners": sorted(set(record["owners"] if record else []) | {owner})}
    activate_portable(record)
    state["records"][key] = record
    return record

def activate_portable(record):
    base = child(SYSTEM_ROOT, record["name"])
    current = base / "current"
    require(not current.exists() or current.is_symlink(), "unmanaged_system_path")
    temporary = base / "current.new"
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(Path(record["directory"]).name, target_is_directory=True)
    os.replace(temporary, current)
    launcher = Path("/usr/local/bin") / ("batch-app-" + record["name"])
    require(not launcher.exists() or (launcher.is_symlink() and launcher.readlink() == current / "launch"),
            "unmanaged_system_launcher")
    if not launcher.is_symlink(): launcher.symlink_to(current / "launch")

def remove_record(record):
    if record["preexisting"]: return
    if record["manager"] == "portable":
        launcher = Path("/usr/local/bin") / ("batch-app-" + record["name"])
        expected = SYSTEM_ROOT / record["name"] / "current/launch"
        if launcher.is_symlink() and launcher.readlink() == expected: launcher.unlink()
        current = expected.parent
        if current.is_symlink(): current.unlink()
    else:
        current = manager_call(record["manager"], "query", record["name"], check=False)
        require(not current or current == record["version"], "package_changed_externally")
        if current: safe_remove(record["manager"], record["name"])


def prune(state):
    """Retención global acotada; nunca borra rutas suministradas por el descriptor."""
    keep_files, keep_directories = set(), set()
    budget = 2 * 1024 ** 3
    for record in state["records"].values():
        add_current_path(record, keep_files, keep_directories)
        budget = retain_history(record, budget, keep_files, keep_directories)
    prune_cache(keep_files)
    prune_system_directories(keep_directories)

def add_current_path(record, keep_files, keep_directories):
    if record.get("file"):
        keep_files.add(Path(record["file"]))
    if record.get("directory"):
        keep_directories.add(Path(record["directory"]))

def retain_history(record, budget, keep_files, keep_directories):
    retained = []
    for old in record["history"]:
        path = Path(old.get("file") or old["directory"])
        size = path_size(path)
        if path.exists() and len(retained) < 2 and size <= budget:
            retained.append(old)
            budget -= size
            (keep_files if old.get("file") else keep_directories).add(path)
    record["history"] = retained
    return budget

def path_size(path):
    if not path.exists():
        return 0
    if path.is_file():
        return path.stat().st_size
    return sum(item.stat().st_size for item in path.rglob("*")
               if item.is_file() and not item.is_symlink())

def prune_cache(keep_files):
    cache = ROOT / "cache"
    if cache.exists():
        for path in cache.iterdir():
            if path.is_file() and not path.is_symlink() and path not in keep_files:
                path.unlink()

def prune_system_directories(keep_directories):
    if SYSTEM_ROOT.exists() and not SYSTEM_ROOT.is_symlink():
        for app in SYSTEM_ROOT.iterdir():
            if not app.is_dir() or app.is_symlink(): continue
            prune_application_directories(app, keep_directories)
            if not any(app.iterdir()): app.rmdir()

def prune_application_directories(app, keep_directories):
    for path in app.iterdir():
        if path.is_dir() and not path.is_symlink() and path not in keep_directories:
            require(path.resolve().is_relative_to(SYSTEM_ROOT), "prune_escape")
            shutil.rmtree(path)

def load_request():
    request = json.loads(sys.stdin.read(2 * 1024 ** 2))
    component = validate_component(request["component"])
    uid = int(request["uid"])
    require(uid > 0 and (not os.environ.get("SUDO_UID") or uid == int(os.environ["SUDO_UID"])),
            "owner_mismatch")
    require(ID.fullmatch(request["bundle"]) and ID.fullmatch(request["transaction"]),
            "invalid_transaction")
    manager = detect()["manager"]
    require(manager == request["manager"], "manager_mismatch")
    owner = f'{uid}:{request["bundle"]}:{component["appId"]}'
    return request, component, uid, manager, owner

def commit_transaction(state, state_file, journal):
    if journal.exists():
        prune(state)
        atomic_json(state_file, state)
        journal.unlink()
    print("{}")

def restore_transaction(state, state_file, journal, uid, bundle):
    if not journal.exists():
        print("{}")
        return
    before = read_json(journal)
    prefix = str(uid) + ":" + bundle + ":"
    intents = before.get("intents", {})
    for key in set(state["records"]) | set(before["records"]) | set(intents):
        restore_record(state, before, intents, key, prefix)
    prune(state)
    atomic_json(state_file, state)
    journal.unlink()
    print("{}")

def restore_record(state, before, intents, key, prefix):
    current, prior = state["records"].get(key), before["records"].get(key)
    if current == prior and key not in intents:
        return
    if current == prior and key in intents:
        current = intents[key]
    owners = set((current or {}).get("owners", [])) | set((prior or {}).get("owners", []))
    require(any(owner.startswith(prefix) for owner in owners), "restore_owner_mismatch")
    if prior:
        restore_prior_record(state, key, prior, current)
    elif current:
        remove_record(current)
        state["records"].pop(key, None)

def restore_prior_record(state, key, prior, current):
    if prior["manager"] == "portable":
        activate_portable(prior)
    elif not prior["preexisting"]:
        installed = manager_call(prior["manager"], "query", prior["name"], check=False)
        require(installed in (None, prior["version"], (current or {}).get("version")),
                "package_changed_externally")
        if installed != prior["version"]:
            require(prior.get("file") and Path(prior["file"]).is_file(),
                    "rollback_file_unavailable")
            manager_call(prior["manager"], "downgrade", prior["file"])
    state["records"][key] = prior

def remember_intent(journal, key, expected):
    snapshot = read_json(journal)
    snapshot.setdefault("intents", {})[key] = copy.deepcopy(expected)
    atomic_json(journal, snapshot)

def native_intent(manager, package, payload, owner, state, journal):
    key = manager + ":" + package
    installed = manager_call(manager, "query", package, check=False)
    prior = state["records"].get(key)
    expected = copy.deepcopy(prior) if prior else {
        "manager": manager, "name": package, "preexisting": bool(installed),
        "version": installed, "file": None, "history": [], "owners": [],
    }
    expected["owners"] = sorted(set(expected["owners"]) | {owner})
    if payload and not expected["preexisting"]:
        expected.update(version=manager_call(manager, "version", str(payload)), file=str(payload))
    remember_intent(journal, key, expected)

def run_dependencies(component, manager, owner, state, journal, state_file):
    for package in component["profile"].get("systemPackages", {}).get(manager, []):
        require(PACKAGE.fullmatch(package), "invalid_dependency_package")
        native_intent(manager, package, None, owner, state, journal)
        native_record(manager, package, None, owner, state, dependency=True)
        atomic_json(state_file, state)

def run_install(request, component, manager, owner, state, journal):
    source = Path(request["file"])
    require(source.is_file(), "missing_payload")
    cache = ROOT / "cache"
    cache.mkdir(mode=0o755, exist_ok=True)
    payload = cached_payload(source, component, cache)
    check_hash(payload, component["sha256"])
    strategy = component["profile"]["strategy"]
    inspect(strategy, payload)
    if strategy in ("deb", "rpm", "arch"):
        return install_native(manager, owner, state, journal, payload, strategy)
    remember_intent(journal, "portable:" + component["appId"], {
        "manager": "portable", "name": component["appId"], "preexisting": False,
        "version": component["sha256"], "owners": [owner], "history": [],
        "directory": str(SYSTEM_ROOT / component["appId"] / component["sha256"]),
    })
    return system_portable(component, payload, owner, state)

def cached_payload(source, component, cache):
    suffix = Path(component["filename"]).name
    payload = cache / (component["sha256"] + "-" + suffix)
    if payload.exists():
        return payload
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as stream:
        temporary = Path(stream.name)
    try:
        shutil.copyfile(source, temporary)
        check_hash(temporary, component["sha256"])
        os.replace(temporary, payload)
        payload.chmod(0o644)
    finally:
        temporary.unlink(missing_ok=True)
    return payload

def install_native(manager, owner, state, journal, payload, strategy):
    require((strategy == "deb" and manager == "apt")
            or (strategy == "rpm" and manager in ("dnf", "zypper"))
            or (strategy == "arch" and manager == "pacman"), "incompatible_package")
    architecture = manager_call(manager, "architecture", str(payload))
    require(ARCHES.get(architecture) in ("any", detect()["architecture"]),
            "package_architecture_mismatch")
    package = manager_call(manager, "identify", str(payload))
    require(PACKAGE.fullmatch(package), "invalid_native_package_name")
    native_intent(manager, package, payload, owner, state, journal)
    return native_record(manager, package, payload, owner, state)

def run_remove(owner, state, journal):
    for key, entry in state["records"].copy().items():
        if owner not in entry["owners"]:
            continue
        remember_intent(journal, key, entry)
        if len(entry["owners"]) == 1:
            remove_record(entry)
            del state["records"][key]
        else:
            entry["owners"].remove(owner)

def run_rollback(manager, owner, state, journal):
    candidates = [record for record in state["records"].values()
                  if owner in record["owners"] and record["history"]]
    require(candidates, "rollback_version_unavailable")
    for entry in candidates:
        remember_intent(journal, entry["manager"] + ":" + entry["name"],
                        {**entry, **entry["history"][0]})
        old = entry["history"].pop(0)
        if entry["manager"] == "portable":
            entry["history"].insert(0, {"directory": entry["directory"], "version": entry["version"]})
            entry.update(old)
            activate_portable(entry)
        else:
            require(Path(old["file"]).is_file(), "rollback_file_unavailable")
            manager_call(manager, "downgrade", old["file"])
            entry["history"].insert(0, {"file": entry["file"], "version": entry["version"]})
            entry.update(old)

def run_operation(request, component, manager, owner, state, journal, state_file):
    action = request["action"]
    if action == "dependencies":
        run_dependencies(component, manager, owner, state, journal, state_file)
        return {}
    if action == "install":
        return run_install(request, component, manager, owner, state, journal)
    if action == "remove":
        run_remove(owner, state, journal)
        return {}
    if action == "rollback":
        run_rollback(manager, owner, state, journal)
        return {}
    raise ValueError("unknown_system_operation")

def main():
    require(os.geteuid() == 0, "root_required")
    request, component, uid, manager, owner = load_request()
    action = request["action"]
    ROOT.mkdir(mode=0o755, parents=True, exist_ok=True)
    require(not ROOT.is_symlink() and ROOT.stat().st_uid == 0, "unsafe_system_registry")
    with open(ROOT / "lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        state_file = ROOT / "state.json"
        state = read_json(state_file) if state_file.exists() else {"records": {}}
        journal_dir = ROOT / "transactions"
        journal_dir.mkdir(mode=0o700, exist_ok=True)
        journal = journal_dir / (str(uid) + "-" + request["transaction"] + ".json")
        # El bloqueo lógico cubre TODA la transacción, incluso entre invocaciones sudo.
        # Una interrupción conserva el diario para que su propietario pueda recuperarlo.
        require(not any(p != journal for p in journal_dir.glob("*.json")), "system_transaction_busy")
        if action == "commit":
            commit_transaction(state, state_file, journal)
            return
        if action == "restore":
            restore_transaction(state, state_file, journal, uid, request["bundle"])
            return
        if not journal.exists(): atomic_json(journal, copy.deepcopy(state))
        record = {}
        try:
            record = run_operation(
                    request, component, manager, owner, state, journal, state_file)
            atomic_json(state_file, state)
        except BaseException:
            # Guardar el progreso parcial; restore utiliza el snapshot anterior.
            atomic_json(state_file, state)
            raise
        print(json.dumps({"preexisting": record.get("preexisting", False)}))

if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(str(error) if isinstance(error, ValueError) else "system_operation_failed", file=sys.stderr)
        sys.exit(1)
