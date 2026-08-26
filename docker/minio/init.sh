#!/bin/sh
set -eu

render_policy() {
  input_path=$1
  output_path=$2
  : > "$output_path"
  while IFS= read -r line || [ -n "$line" ]; do
    case $line in
      *"__BUCKET__"*)
        prefix=${line%%__BUCKET__*}
        suffix=${line#*__BUCKET__}
        printf '%s%s%s\n' "$prefix" "$MINIO_ZIP_BUCKET" "$suffix" >> "$output_path"
        ;;
      *)
        printf '%s\n' "$line" >> "$output_path"
        ;;
    esac
  done < "$input_path"
}

if [ "${1:-}" = "--render-policy" ]; then
  if [ "$#" -ne 3 ]; then
    echo 'Uso: init.sh --render-policy <entrada> <salida>' >&2
    exit 2
  fi
  render_policy "$2" "$3"
  exit 0
fi

attempts=0
until mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"; do
  attempts=$((attempts + 1))
  if [ "$attempts" -ge 30 ]; then
    echo 'MinIO no está disponible para inicialización.' >&2
    exit 1
  fi
  sleep 2
done

mc mb --ignore-existing "local/$MINIO_ZIP_BUCKET"
mc ilm rule import "local/$MINIO_ZIP_BUCKET" < /config/lifecycle-zips.json
mc quota set "local/$MINIO_ZIP_BUCKET" --size "$MINIO_ZIP_QUOTA"

render_policy /config/core-policy.json /tmp/core-policy.json
render_policy /config/worker-policy.json /tmp/worker-policy.json
mc admin policy create local batch-core /tmp/core-policy.json
mc admin policy create local batch-worker /tmp/worker-policy.json
mc admin user add local "$MINIO_CORE_ACCESS_KEY" "$MINIO_CORE_SECRET_KEY"
mc admin user add local "$MINIO_WORKER_ACCESS_KEY" "$MINIO_WORKER_SECRET_KEY"
mc admin policy attach local batch-core --user "$MINIO_CORE_ACCESS_KEY"
mc admin policy attach local batch-worker --user "$MINIO_WORKER_ACCESS_KEY"
