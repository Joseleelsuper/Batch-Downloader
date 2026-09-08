#!/usr/bin/env bash
set -euo pipefail
runtime=services/download-worker/src/main/resources/linux-installer
mapfile -d '' scripts < <(find "$runtime" -name '*.sh' -print0)
shellcheck --external-sources --source-path=SCRIPTDIR "${scripts[@]}" "$runtime/bin/batch-linux-installer"
for script in "${scripts[@]}"; do bash -n "$script"; done
python3 -B -m unittest discover -s scripts/linux-installer/tests -v
bats scripts/linux-installer/tests/cli.bats
