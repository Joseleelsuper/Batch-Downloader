#!/usr/bin/env bash
bd_detect_os() {
  python3 "$BD_RUNTIME/lib/data.py" detect
}
