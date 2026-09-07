#!/usr/bin/env bash
bd_lock() {
  command -v flock >/dev/null || return 2
  exec {BD_LOCK_FD}>"$1"
  flock -n "$BD_LOCK_FD"
}
