#!/usr/bin/env bats

setup() {
  runtime="$BATS_TEST_DIRNAME/../../../services/download-worker/src/main/resources/linux-installer"
}

@test "CLI: ayuda disponible sin instalar ni pedir sudo" {
  run bash "$runtime/install.sh" --help
  [ "$status" -eq 0 ]
  [[ "$output" == *"--dry-run"* ]]
}

@test "CLI: los argumentos desconocidos fallan antes de instalar" {
  run bash "$runtime/install.sh" --unknown-option
  [ "$status" -ne 0 ]
}

@test "plugins: un nombre de paquete no puede convertirse en una opción" {
  for manager in apt dnf pacman zypper; do
    run bash "$runtime/plugins/package-managers/$manager.sh" remove --help
    [ "$status" -ne 0 ]
  done
}
