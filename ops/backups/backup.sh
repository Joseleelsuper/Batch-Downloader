#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

readonly CONFIG_FILE="${BATCH_BACKUP_CONFIG:-/etc/batch-downloader/backup.env}"
readonly BACKUP_ROOT="${BATCH_BACKUP_ROOT:-/var/backups/batch-downloader}"
readonly PROJECT="${BATCH_COMPOSE_PROJECT:-batch-downloader}"
readonly SEMANTIC_VOLUME="${SEMANTIC_MODELS_VOLUME_NAME:-batch-downloader_semantic_models}"

if (( EUID != 0 )); then
  echo "El backup debe ejecutarse como root." >&2
  exit 1
fi
if [[ ! -r "${CONFIG_FILE}" ]]; then
  echo "No se puede leer ${CONFIG_FILE}." >&2
  exit 1
fi
source "${CONFIG_FILE}"
: "${OCI_S3_ENDPOINT:?Falta OCI_S3_ENDPOINT}"
: "${OCI_S3_REGION:?Falta OCI_S3_REGION}"
: "${OCI_BACKUP_BUCKET:?Falta OCI_BACKUP_BUCKET}"
: "${AWS_ACCESS_KEY_ID:?Falta AWS_ACCESS_KEY_ID}"
: "${AWS_SECRET_ACCESS_KEY:?Falta AWS_SECRET_ACCESS_KEY}"
: "${RESTIC_PASSWORD:?Falta RESTIC_PASSWORD}"
export AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY RESTIC_PASSWORD
export AWS_DEFAULT_REGION="${OCI_S3_REGION}" AWS_REGION="${OCI_S3_REGION}"

container_for() {
  local service="$1"
  local container
  container="$(docker ps -aq \
    --filter "label=com.docker.compose.project=${PROJECT}" \
    --filter "label=com.docker.compose.service=${service}" | head -n 1)"
  if [[ -z "${container}" ]]; then
    echo "No existe el contenedor ${service} del proyecto ${PROJECT}." >&2
    return 1
  fi
  printf '%s\n' "${container}"
}

repository_for() {
  printf 's3:%s/%s/%s\n' \
    "${OCI_S3_ENDPOINT%/}" "${OCI_BACKUP_BUCKET}" "$1"
}

remote_backup() {
  local repository="$1"
  local path="$2"
  local keep="$3"
  export RESTIC_REPOSITORY
  RESTIC_REPOSITORY="$(repository_for "${repository}")"
  if ! restic snapshots --no-lock >/dev/null 2>&1; then
    restic init
  fi
  restic backup "${path}" --tag "${repository}"
  restic forget --keep-daily "${keep}" --prune
}

retain_local() {
  local directory="$1"
  local keep="$2"
  mapfile -t files < <(find "${directory}" -maxdepth 1 -type f -printf '%T@ %p\n' \
    | sort -rn | cut -d' ' -f2-)
  if (( ${#files[@]} > keep )); then
    rm -- "${files[@]:keep}"
  fi
}

database_backup() {
  local database="$1"
  local directory="${BACKUP_ROOT}/${database}"
  local timestamp output container
  timestamp="$(date +%Y%m%dT%H%M%S%z)"
  mkdir -p "${directory}"
  container="$(container_for "${database}")"
  if [[ "${database}" == "mysql" ]]; then
    output="${directory}/mysql-${timestamp}.sql.gz"
    docker exec "${container}" sh -c \
      'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --routines --events "$MYSQL_DATABASE"' \
      | gzip -9 > "${output}"
  else
    output="${directory}/postgres-${timestamp}.dump"
    docker exec "${container}" sh -c \
      'exec pg_dump --format=custom --username="$POSTGRES_USER" "$POSTGRES_DB"' \
      > "${output}"
  fi
  test -s "${output}"
  remote_backup "${database}" "${output}" 30
  retain_local "${directory}" 2
}

cold_volume_backup() {
  local service="$1"
  local volume="$2"
  local repository="$3"
  local directory="${BACKUP_ROOT}/${repository}"
  local timestamp output container mountpoint was_running=false
  local archive_status=0 restart_status=0
  timestamp="$(date +%Y%m%dT%H%M%S%z)"
  mkdir -p "${directory}"
  container="$(container_for "${service}")"
  mountpoint="$(docker volume inspect --format '{{.Mountpoint}}' "${volume}")"
  output="${directory}/${repository}-${timestamp}.tar.gz"
  if [[ "$(docker inspect --format '{{.State.Running}}' "${container}")" == "true" ]]; then
    was_running=true
    docker stop --time 30 "${container}" >/dev/null
  fi
  tar --one-file-system -C "${mountpoint}" -czf "${output}" . || archive_status=$?
  if [[ "${was_running}" == true ]]; then
    docker start "${container}" >/dev/null || restart_status=$?
  fi
  if (( restart_status != 0 )); then
    echo "No se pudo reiniciar ${service} después de la copia; requiere intervención inmediata." >&2
    return "${restart_status}"
  fi
  if (( archive_status != 0 )); then
    return "${archive_status}"
  fi
  test -s "${output}"
  remote_backup "${repository}" "${output}" 14
  retain_local "${directory}" 1
}

model_backup() {
  local mountpoint directory timestamp output
  mountpoint="$(docker volume inspect --format '{{.Mountpoint}}' "${SEMANTIC_VOLUME}")"
  if [[ ! -f "${mountpoint}/current/batch-model.json" ]]; then
    echo "El volumen no contiene un modelo current validado." >&2
    exit 1
  fi
  directory="${BACKUP_ROOT}/semantic-model"
  timestamp="$(date +%Y%m%dT%H%M%S%z)"
  output="${directory}/semantic-model-${timestamp}.tar.gz"
  mkdir -p "${directory}"
  tar --one-file-system -C "${mountpoint}" -czf "${output}" current
  remote_backup "semantic-model" "${output}" 3
  retain_local "${directory}" 3
}

case "${1:-}" in
  mysql | postgres)
    database_backup "$1"
    ;;
  rabbitmq)
    cold_volume_backup rabbitmq "${PROJECT}_rabbitmq_data" rabbitmq
    ;;
  notification)
    cold_volume_backup notification-service \
      "${PROJECT}_notification_service_data" notification
    ;;
  download-worker)
    cold_volume_backup download-worker \
      "${PROJECT}_download_worker_data" download-worker
    ;;
  model)
    model_backup
    ;;
  *)
    echo "Uso: $0 {mysql|postgres|rabbitmq|notification|download-worker|model}" >&2
    exit 2
    ;;
esac
