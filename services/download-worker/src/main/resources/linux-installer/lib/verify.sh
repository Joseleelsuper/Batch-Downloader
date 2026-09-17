#!/usr/bin/env bash
bd_verify() {
  local expected=$1 file=$2
  printf '%s  %s\n' "$expected" "$file" | sha256sum --check --status
  return $?
}
