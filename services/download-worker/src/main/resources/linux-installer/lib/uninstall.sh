#!/usr/bin/env bash
bd_uninstall_package() {
  local manager=$1 package=$2
  bd_system_package remove "$manager" "$package"
  return $?
}
