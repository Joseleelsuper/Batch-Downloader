#!/usr/bin/env bash
set -Eeuo pipefail

readonly BACKUP_ROOT="${BATCH_BACKUP_ROOT:-/var/backups/batch-downloader}"
readonly MYSQL_IMAGE="${MYSQL_RESTORE_IMAGE:-mysql:8.4}"
readonly POSTGRES_IMAGE="${POSTGRES_RESTORE_IMAGE:-pgvector/pgvector:pg16}"
readonly MYSQL_CONTAINER="batch-downloader-restore-mysql-$$"
readonly POSTGRES_CONTAINER="batch-downloader-restore-postgres-$$"

if (( EUID != 0 )); then
  echo "La verificación debe ejecutarse como root." >&2
  exit 1
fi

cleanup() {
  docker rm -f "${MYSQL_CONTAINER}" "${POSTGRES_CONTAINER}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

latest_backup() {
  local directory="$1"
  local pattern="$2"
  local latest
  latest="$(find "${directory}" -maxdepth 1 -type f -name "${pattern}" -printf '%T@ %p\n' \
    | sort -rn | head -n 1 | cut -d' ' -f2-)"
  if [[ -z "${latest}" ]]; then
    echo "No existe una copia ${pattern} en ${directory}." >&2
    return 1
  fi
  printf '%s\n' "${latest}"
}

wait_for_mysql() {
  local attempt
  for attempt in {1..60}; do
    if docker exec "${MYSQL_CONTAINER}" mysqladmin ping -uroot -prestore-only --silent; then
      return
    fi
    sleep 2
  done
  echo "MySQL desechable no quedó preparado." >&2
  return 1
}

wait_for_postgres() {
  local attempt
  for attempt in {1..60}; do
    if docker exec "${POSTGRES_CONTAINER}" pg_isready -U restore -d restore >/dev/null; then
      return
    fi
    sleep 2
  done
  echo "PostgreSQL desechable no quedó preparado." >&2
  return 1
}

verify_mysql() {
  local backup
  backup="$(latest_backup "${BACKUP_ROOT}/mysql" 'mysql-*.sql.gz')"
  docker run --detach --name "${MYSQL_CONTAINER}" --network none \
    --tmpfs /var/lib/mysql:rw,size=3g \
    -e MYSQL_ROOT_PASSWORD=restore-only \
    -e MYSQL_DATABASE=restore \
    "${MYSQL_IMAGE}" >/dev/null
  wait_for_mysql
  gzip --decompress --stdout "${backup}" \
    | docker exec --interactive "${MYSQL_CONTAINER}" mysql -uroot -prestore-only restore
  docker exec "${MYSQL_CONTAINER}" mysqlcheck -uroot -prestore-only --databases restore >/dev/null
  docker rm -f "${MYSQL_CONTAINER}" >/dev/null
  echo "MySQL restaurado y comprobado desde ${backup}."
}

verify_postgres() {
  local backup
  backup="$(latest_backup "${BACKUP_ROOT}/postgres" 'postgres-*.dump')"
  docker run --detach --name "${POSTGRES_CONTAINER}" --network none \
    --tmpfs /var/lib/postgresql/data:rw,size=3g \
    -e POSTGRES_USER=restore \
    -e POSTGRES_PASSWORD=restore-only \
    -e POSTGRES_DB=restore \
    "${POSTGRES_IMAGE}" >/dev/null
  wait_for_postgres
  docker exec --interactive "${POSTGRES_CONTAINER}" \
    pg_restore --exit-on-error --no-owner --username=restore --dbname=restore < "${backup}"
  docker exec "${POSTGRES_CONTAINER}" \
    psql --username=restore --dbname=restore --command='SELECT 1' >/dev/null
  docker rm -f "${POSTGRES_CONTAINER}" >/dev/null
  echo "PostgreSQL restaurado y comprobado desde ${backup}."
}

case "${1:-all}" in
  mysql)
    verify_mysql
    ;;
  postgres)
    verify_postgres
    ;;
  all)
    verify_mysql
    verify_postgres
    ;;
  *)
    echo "Uso: $0 [mysql|postgres|all]" >&2
    exit 2
    ;;
esac
