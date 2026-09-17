#!/usr/bin/env bash
bd_lock() {
  local path=$1
  command -v flock >/dev/null || return 2
  exec {BD_LOCK_FD}>"$path"
  flock -n "$BD_LOCK_FD"
  return $?
}
