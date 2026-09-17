#!/usr/bin/env bash
bd_log() {
  local level=$1 message=$2
  printf '%s [%s] %s\n' "$(date -u +%FT%TZ)" "$level" "$message" >&2
  return $?
}
