#!/usr/bin/env bash
bd_parse_args() {
  # La validación exhaustiva y los defaults viven en el parser único del runtime.
  if [[ ${1:-} == --help || ${1:-} == -h ]]; then
    printf '%s\n' 'install|uninstall|update|rollback|list [bundleId] [--silent] [--dry-run] [--components IDs] [--scope user|system] [--no-tui] [--purge]'
    exit 0
  fi
}
