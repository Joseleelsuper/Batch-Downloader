"""Mide código propio por componente, separando comentarios, pruebas y migraciones.

La misma clasificación se aplica a un commit y al árbol de trabajo, incluidos archivos
nuevos. El código compartido y las herramientas cuentan: mover una función no ahorra líneas.
"""

import argparse
import ast
import hashlib
import json
import re
import subprocess
from collections import Counter, defaultdict
from pathlib import Path

from pygments.lexers import BashLexer, TextLexer, get_lexer_for_filename
from pygments.token import Comment

ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = {".java", ".py", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs",
              ".css", ".scss", ".sh", ".ps1", ".sql", ".html", ".bats"}
EMPTY_DESCRIPTION = re.compile(r"Implementa el componente|Ejecuta la operación")


def sources(ref: str | None) -> dict[str, bytes]:
    """Lee fuentes de Git o del disco sin incluir dependencias ni artefactos ignorados."""
    command = ["ls-tree", "-r", "--name-only", "-z", ref] if ref else [
        "ls-files", "--cached", "--others", "--exclude-standard", "-z"]
    names = subprocess.check_output(["git", *command], cwd=ROOT).decode().split("\0")
    names = sorted({name for name in names if name and
                    (Path(name).suffix in EXTENSIONS or
                     Path(name).name == "batch-linux-installer")})
    if not ref:
        return {name: (ROOT / name).read_bytes() for name in names if (ROOT / name).is_file()}
    payload = "".join(f"{ref}:{name}\n" for name in names).encode()
    result = subprocess.run(["git", "cat-file", "--batch"], input=payload,
                            capture_output=True, check=True, cwd=ROOT).stdout
    offset, contents = 0, {}
    for name in names:
        end = result.index(b"\n", offset)
        size = int(result[offset:end].split()[-1])
        contents[name] = result[end + 1:end + 1 + size]
        offset = end + size + 2
    return contents


def classify(name: str, content: bytes) -> dict[str, int]:
    """Clasifica cada línea; una línea con código y comentario se cuenta como código."""
    text = content.decode("utf-8-sig")
    lines, doclines, documentation = text.splitlines(), set(), []
    if name.endswith(".py"):
        for node in ast.walk(ast.parse(text)):
            if (isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant)
                    and isinstance(node.value.value, str)):
                doclines.update(range(node.lineno, node.end_lineno + 1))
                documentation.append(node.value.value)
    lexer = (BashLexer() if Path(name).name == "batch-linux-installer" else
             TextLexer() if name.endswith(".bats") else get_lexer_for_filename(name))
    code, comments, line = set(), set(), 1
    for _, token, value in lexer.get_tokens_unprocessed(text):
        if token in Comment:
            documentation.append(value)
        parts = value.split("\n")
        for index, part in enumerate(parts):
            if part.strip():
                (comments if token in Comment or line in doclines else code).add(line)
            if index < len(parts) - 1:
                line += 1
    return {"files": 1, "physical": len(lines), "code": len(code),
            "comments": len(comments - code), "blank": sum(not s.strip() for s in lines),
            "empty_descriptions": len(EMPTY_DESCRIPTION.findall("\n".join(documentation)))}


def inventory(ref: str | None) -> dict:
    """Agrega fuentes con la misma regla de pertenencia usada en la línea base."""
    modules = defaultdict(lambda: defaultdict(Counter))
    totals, roles, hashes = Counter(), defaultdict(Counter), {}
    for name, content in sources(ref).items():
        parts = name.split("/")
        module = "/".join(parts[:2]) if parts[0] in {"api", "services", "shared"} else "support"
        role = ("tests" if re.search(r"(^|/)(tests?|tst)(/|$)|\.(test|spec)\.[^.]+$", name)
                else "migrations" if re.search(r"/alembic/|/db/migration/|/migrations/", name)
                else "production")
        metrics = classify(name, content)
        modules[module][role].update(metrics)
        totals.update(metrics)
        roles[role].update(metrics)
        if "/db/migration/" in name:
            hashes[name] = hashlib.sha256(content).hexdigest()
    return {"schema_version": 1, "ref": ref or "working-tree", "modules": modules,
            "roles": roles, "total": totals, "flyway_sha256": hashes}


def main() -> None:
    """Emite JSON o lo guarda en la ruta explícita; opcionalmente exige reducción neta."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    result = inventory(args.ref)
    failed = False
    if args.baseline:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
        delta = result["roles"]["production"]["code"] - baseline["roles"]["production"]["code"]
        result["production_code_delta"] = delta
        # Los bytes CRLF del checkout equivalen a LF en Git; el chequeo real usa los blobs.
        changed_migrations = subprocess.check_output(
            ["git", "diff", baseline["ref"], "--numstat", "--", "services/core-api/src/main/resources/db/migration"],
            cwd=ROOT).decode().strip()
        result["historical_flyway_changed"] = bool(changed_migrations)
        failed = delta >= 0 or bool(changed_migrations)
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded)
    if args.check and failed:
        raise SystemExit("La reducción neta o la integridad de Flyway no se cumple.")


if __name__ == "__main__":
    main()
