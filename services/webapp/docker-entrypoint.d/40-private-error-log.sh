#!/bin/sh
set -eu

# Los accesos públicos no guardan IP ni URL. Los errores detallados viven solo en este archivo.
umask 077
error_file=/var/log/nginx/private-error.log
: > "$error_file"
chmod 600 "$error_file"
(
    while sleep 3600; do
        : > "$error_file"
    done
) &
