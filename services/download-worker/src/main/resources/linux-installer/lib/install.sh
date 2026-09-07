#!/usr/bin/env bash
bd_install_file() {
  local manager=$1 file=$2
  bash "$BD_RUNTIME/plugins/package-managers/$manager.sh" install "$file"
}
