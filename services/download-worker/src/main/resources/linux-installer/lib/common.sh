#!/usr/bin/env bash
# Fachada del runtime; solo carga módulos distribuidos, nunca datos del catálogo.
BD_RUNTIME="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
export BD_RUNTIME
for bd_module in args logging progress tui detect_os checks download verify dependencies install uninstall state update rollback parallel locks; do
  # shellcheck source=/dev/null
  source "$BD_RUNTIME/lib/$bd_module.sh"
done
bd_main() {
  bd_parse_args "$@"
  bd_pre_checks
  PYTHONDONTWRITEBYTECODE=1 python3 -B "$BD_RUNTIME/lib/runtime.py" "$@"
  return $?
}
