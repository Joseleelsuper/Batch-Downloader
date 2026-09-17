#!/usr/bin/env bash
# Todos los redirects y hosts son validados por el cliente HTTPS común.
bd_download() {
  python3 "$BD_RUNTIME/lib/data.py" download "$@"
  return $?
}
