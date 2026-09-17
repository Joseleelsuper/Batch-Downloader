#!/usr/bin/env bash
bd_progress() {
  local current=$1 total=$2 message=$3
  printf '\r[%s/%s] %s\n' "$current" "$total" "$message"
  return $?
}
