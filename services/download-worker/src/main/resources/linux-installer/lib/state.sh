#!/usr/bin/env bash
bd_list_bundles() {
  python3 "$BD_RUNTIME/lib/runtime.py" list
  return $?
}
