#!/usr/bin/env bash
bd_rollback() { python3 "$BD_RUNTIME/lib/runtime.py" rollback "$@"; }
