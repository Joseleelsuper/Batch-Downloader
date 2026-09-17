#!/usr/bin/env bash
# Espera a todos los hijos y conserva un fallo, aunque otros terminen bien.
bd_wait_all() {
  local pid result=0
  for pid in "$@"; do wait "$pid" || result=1; done
  return "$result"
}
