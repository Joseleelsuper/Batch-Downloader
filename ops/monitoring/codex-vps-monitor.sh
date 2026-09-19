#!/usr/bin/env bash
set -Eeuo pipefail

readonly PATH=/usr/local/bin:/usr/bin:/bin
readonly STATE_DIR=/var/lib/batch-downloader-codex-monitor
readonly SCHEMA=/etc/batch-downloader/codex-vps-monitor.schema.json
readonly CLOUD_ENV_ID=687e99112b548191b1dd1c617706113c

mkdir -p "${STATE_DIR}"
facts_file="$(mktemp)"
result_file="$(mktemp)"
trap 'rm -f "${facts_file}" "${result_file}"' EXIT

{
  echo "captured_at=$(date --iso-8601=seconds)"
  echo "containers:"
  docker ps --format '{{.Names}}|{{.Status}}'
  echo "restart_counts:"
  docker ps --quiet | xargs --no-run-if-empty docker inspect --format '{{.Name}}|{{.RestartCount}}'
  echo "root_filesystem:"
  df -P /
  echo "memory:"
  free -m
  echo "listeners:"
  ss -lnt
  echo "http_checks:"
  curl --silent --show-error --output /dev/null --write-out 'root=%{http_code}\n' --max-time 15 https://batchdownloader.dev/
  curl --silent --show-error --output /dev/null --write-out 'www=%{http_code}\n' --max-time 15 https://www.batchdownloader.dev/
  curl --silent --show-error --output /dev/null --write-out 'downloads_health=%{http_code}\n' --max-time 15 https://downloads.batchdownloader.dev/healthz
  curl --silent --show-error --output /dev/null --write-out 'coolify=%{http_code}\n' --max-time 15 https://deploy.batchdownloader.dev/
  echo "certificates:"
  for hostname in batchdownloader.dev www.batchdownloader.dev downloads.batchdownloader.dev deploy.batchdownloader.dev; do
    printf '%s|' "${hostname}"
    openssl s_client -connect 127.0.0.1:443 -servername "${hostname}" </dev/null 2>/dev/null \
      | openssl x509 -noout -enddate
  done
} >"${facts_file}" 2>&1 || true

prompt="$(printf '%s\n' \
  'Actúa como monitor de infraestructura de Batch Downloader. Analiza exclusivamente las observaciones adjuntas y no ejecutes comandos ni cambies nada.' \
  'Deben existir estos servicios persistentes: Coolify, coolify-db, coolify-redis, coolify-realtime, coolify-proxy, coolify-sentinel, webapp, core-api, scraper-api, scraper-scheduler, MySQL, PostgreSQL, RabbitMQ, MinIO, semantic-service, semantic-indexer, notification-service, download-worker y translation-service. Ignora los contenedores one-shot ya terminados.' \
  'Devuelve status=problem si falta un contenedor esperado, algún contenedor persistente no está healthy, hay más de tres reinicios, el disco raíz alcanza el 75%, la memoria disponible baja del 10%, un certificado vence en 21 días, falla HTTPS, o existe un listener público distinto de TCP 22, 80 o 443.' \
  'Los listeners en 127.0.0.1 y ::1 son internos. Los códigos 200 y 3xx son válidos para root, www y Coolify; downloads_health debe devolver 200.' \
  'No incluyas secretos, variables de entorno, credenciales ni datos de usuarios. Si todo está correcto, devuelve status=ok y un resumen breve.' \
  '' \
  'OBSERVACIONES:'; cat "${facts_file}")"

if ! timeout 15m codex \
  -m gpt-5.6-luna \
  -c 'model_reasoning_effort="max"' \
  -s read-only \
  -a never \
  exec \
  --skip-git-repo-check \
  --ephemeral \
  --color never \
  --output-schema "${SCHEMA}" \
  --output-last-message "${result_file}" \
  "${prompt}"; then
  printf '%s\n' '{"status":"problem","summary":"El monitor Codex no pudo completar el análisis.","details":["Revise journalctl -u batch-downloader-codex-monitor.service"]}' >"${result_file}"
fi

if ! jq -e '.status == "ok" or .status == "problem"' "${result_file}" >/dev/null 2>&1; then
  printf '%s\n' '{"status":"problem","summary":"El monitor Codex devolvió una respuesta inválida.","details":["Revise journalctl -u batch-downloader-codex-monitor.service"]}' >"${result_file}"
fi

install -m 0600 "${result_file}" "${STATE_DIR}/latest.json"
if [[ "$(jq -r '.status' "${result_file}")" == "ok" ]]; then
  exit 0
fi

alert_prompt="$(printf '%s\n' \
  'ALERTA AUTOMÁTICA DEL VPS DE BATCH DOWNLOADER.' \
  'No hagas cambios ni ejecutes pruebas funcionales. Presenta esta incidencia de infraestructura de forma breve, conserva los detalles técnicos y recomienda el siguiente diagnóstico seguro.' \
  '' \
  "$(cat "${result_file}")")"

timeout 5m codex cloud exec \
  --env "${CLOUD_ENV_ID}" \
  --branch main \
  "${alert_prompt}"
