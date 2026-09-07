#!/usr/bin/env bash
# Inspección de datos únicamente. Nunca ejecuta el binario del proveedor.
set -euo pipefail
BD_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
exec python3 "$BD_ROOT/lib/data.py" inspect tarball "${1:?file}"
