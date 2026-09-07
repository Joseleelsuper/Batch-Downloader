#!/usr/bin/env bash
bd_package_name() { [[ $1 =~ ^[a-zA-Z0-9][a-zA-Z0-9+._:-]*$ ]]; }
bd_system_package() {
  local action=$1 manager=$2 package=$3
  bd_package_name "$package" || return 2
  "$BD_RUNTIME/plugins/package-managers/$manager.sh" "$action" "$package"
}
