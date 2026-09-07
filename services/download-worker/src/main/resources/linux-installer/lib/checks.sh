#!/usr/bin/env bash
bd_pre_checks() {
  command -v python3 >/dev/null || { printf '%s\n' 'Falta Python 3.10+. Instálalo con el gestor de tu distribución.' >&2; exit 2; }
  python3 -c 'import sys; sys.exit(0 if sys.version_info >= (3,10) else 2)' || {
    printf '%s\n' 'Se necesita Python 3.10 o posterior.' >&2; exit 2;
  }
}
bd_post_check() { test -e "$1"; }
