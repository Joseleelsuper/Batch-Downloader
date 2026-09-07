#!/usr/bin/env bash
bd_verify() { printf '%s  %s\n' "$1" "$2" | sha256sum --check --status; }
