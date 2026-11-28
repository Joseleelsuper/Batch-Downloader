#!/bin/sh
set -euC

cd "$(dirname "$0")"
templates=$(git ls-files -- ':(glob)**/.env.example')
printf '%s\n' "$templates" | while IFS= read -r template; do
    [ -n "$template" ] || continue
    target=${template%.example}
    [ ! -e "$target" ] && [ ! -L "$target" ] || continue
    cat "$template" > "$target"
    printf 'Creado %s\n' "$target"
done
