#!/usr/bin/env bash
set -Eeuo pipefail

if (( EUID != 0 )); then
  echo "Ejecuta este instalador como root." >&2
  exit 1
fi

readonly SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
apt-get update
apt-get install -y --no-install-recommends cron restic
install -d -m 0700 /etc/batch-downloader /var/backups/batch-downloader
install -m 0750 "${SOURCE_DIR}/backup.sh" /usr/local/sbin/batch-downloader-backup
install -m 0750 "${SOURCE_DIR}/verify-database-restore.sh" \
  /usr/local/sbin/batch-downloader-verify-restore
if [[ ! -e /etc/batch-downloader/backup.env ]]; then
  install -m 0600 "${SOURCE_DIR}/backup.env.example" /etc/batch-downloader/backup.env
fi

cron_tmp="$(mktemp)"
trap 'rm -f "${cron_tmp}"' EXIT
cat > "${cron_tmp}" <<'CRON'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

10 2 * * * root flock -w 21600 /run/batch-downloader-backup.lock batch-downloader-backup mysql
30 2 * * * root flock -w 21600 /run/batch-downloader-backup.lock batch-downloader-backup postgres
0 3 * * * root flock -w 21600 /run/batch-downloader-backup.lock batch-downloader-backup rabbitmq
20 3 * * * root flock -w 21600 /run/batch-downloader-backup.lock batch-downloader-backup notification
40 3 * * * root flock -w 21600 /run/batch-downloader-backup.lock batch-downloader-backup download-worker
CRON
install -m 0644 "${cron_tmp}" /etc/cron.d/batch-downloader-backups
systemctl enable --now cron.service
echo "Edita /etc/batch-downloader/backup.env antes de ejecutar las copias."
