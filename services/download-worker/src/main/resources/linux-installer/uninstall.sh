#!/usr/bin/env bash
set -euo pipefail
BD_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/common.sh
source "$BD_ROOT/lib/common.sh"
bd_main uninstall "$@"
