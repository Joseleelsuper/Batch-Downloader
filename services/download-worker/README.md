# Download Worker

Download Worker consume los comandos versionados de RabbitMQ, resuelve las
fuentes en el scraper, descarga los instaladores en temporales acotados, genera
el ZIP en MinIO y publica el progreso de vuelta a Core. Las URLs resueltas nunca
se incluyen en eventos, manifests ni logs.

## Pipeline y capacidad

Las fases `RESOLVING`, `DOWNLOADING` y `PACKAGING` son independientes. Un
trabajo termina todas sus descargas antes de adquirir el semáforo de
empaquetado; por tanto, esperar a crear o subir el ZIP no bloquea plazas de
descarga remota.

El perfil incluido para un VPS de 8 vCore, 24 GB de RAM y 200 GB de NVMe usa:

| Variable | Valor | Función |
| --- | ---: | --- |
| `DOWNLOAD_WORKER_JOB_CONCURRENCY` | `8` | Consumidores y plazas de trabajos normales. |
| `DOWNLOAD_WORKER_CONCURRENCY` | `16` | Descargas HTTP globales. |
| `DOWNLOAD_WORKER_PER_JOB_CONCURRENCY` | `2` | Descargas simultáneas dentro de un trabajo. |
| `DOWNLOAD_WORKER_PACKAGING_CONCURRENCY` | `4` | ZIP y subidas a MinIO simultáneos. |
| `DOWNLOAD_WORKER_ZIP_LEVEL` | `0` | ZIP sin compresión para reducir CPU y temporales. |
| `DOWNLOAD_WORKER_LARGE_JOB_THRESHOLD` | `2GB` | Umbral de ejecución exclusiva. |
| `DOWNLOAD_WORKER_MAX_TOTAL_SIZE` | `20GB` | Tamaño máximo de un trabajo. |
| `DOWNLOAD_WORKER_MIN_FREE_SPACE` | `30GB` | Reserva mínima del volumen temporal. |
| `MINIO_ZIP_QUOTA` | `120GB` | Cuota lógica y física del bucket. |

Un trabajo con tamaño total declarado de hasta 2 GB consume una plaza. Si
supera el umbral o falta cualquier tamaño, consume las ocho plazas y se ejecuta
en exclusiva. Cada hostname de origen dispone, además, de un semáforo justo de
dos transferencias.

Antes de iniciar el tráfico se reserva el tamaño temporal estimado completo y
el espacio estimado del artefacto. La admisión interna comprueba también el
espacio utilizable, los bytes ya almacenados en MinIO, las reservas en vuelo y
la cuota. Si no hay margen seguro:

- se publica `download.job.deferred` con `waitReason` y `retryAt`;
- el mensaje pasa a `download-worker.download.job.capacity-wait.v1`;
- su TTL de 30 segundos lo devuelve a la cola principal;
- el trabajo permanece `QUEUED` y no consume un intento de fallo.

La cola principal tiene ocho consumidores y `prefetch=1`. Las cancelaciones
usan un container independiente con dos consumidores y `prefetch=1`, de modo
que no se multiplican por la concurrencia de trabajos. La espera de semáforos
comprueba periódicamente la cancelación.

## Política HTTP

Cada instalador puede reintentarse dos veces, además del intento inicial, solo
ante timeout, HTTP `408`, `429` o `5xx`. `Retry-After` se respeta hasta un máximo
de 30 segundos. Otros `4xx`, hashes incorrectos, límites de tamaño y datos
inválidos no se reintentan. Cada intento fallido elimina su temporal y el
siguiente empieza desde cero; no se implementa reanudación parcial.

Los límites existentes de DNS público, HTTPS, redirecciones y presupuesto total
se aplican en cada intento. El wrapper de reintento queda dentro de la reserva
global, por trabajo y por hostname, por lo que ningún retry elude los límites de
concurrencia.

## Artefactos y eventos

El worker escribe `jobs/{jobId}/bundle.zip` con una clave determinista y publica
eventos de esquema 1:

- `download.job.progressed`, para progreso e items individuales;
- `download.job.ready`, con `artifactSizeBytes` y `artifactSha256` del ZIP;
- `download.job.deferred`, para esperas no terminales de capacidad;
- `download.job.failed`, para errores terminales.

La inbox hace idempotente cada `eventId`. Una redelivery sobrescribe el mismo
objeto y Core conserva compatibilidad con eventos `ready` anteriores que no
incluyan metadatos. La retención efectiva del worker y Core es de 6 horas; el
lifecycle de MinIO elimina objetos a las 24 horas como red de seguridad y el
barrido server-side elimina multipart inactivos tras 24 horas.

El worker usa `MINIO_WORKER_ACCESS_KEY` y `MINIO_WORKER_SECRET_KEY`, cuyo usuario
solo puede listar el bucket y crear, borrar o gestionar multipart bajo
`jobs/*`. No utiliza las credenciales root.

## Métricas y alertas

`/actuator/prometheus` publica, entre otras:

- `download_worker_queue_depth`, `download_worker_capacity_wait_queue_depth`,
  `download_worker_queue_consumers` y `download_worker_queue_wait_seconds`;
- `download_worker_active_jobs`, `download_worker_active_downloads` y
  `download_worker_host_active_downloads`, junto con
  `download_worker_job_capacity_wait_seconds` y
  `download_worker_host_wait_seconds`;
- `download_worker_active_packagings` y
  `download_worker_packaging_wait_seconds`;
- `download_worker_temporary_bytes`, `download_worker_disk_reserved_bytes`,
  `download_worker_disk_usable_bytes` y
  `download_worker_disk_minimum_free_bytes`;
- `download_worker_artifact_stored_bytes`,
  `download_worker_artifact_reserved_bytes` y
  `download_worker_artifact_quota_bytes`;
- `download_worker_capacity_deferred_total` y
  `download_worker_remote_retries_total`.

Las reglas de ejemplo están en `docker/prometheus/download-alerts.yml`: cola por
encima de 40 durante 5 minutos, cuota al 90 %, menos de 30 GB disponibles y
crecimiento de aplazamientos por capacidad.

## Verificación

Desde la raíz del repositorio:

```bash
mvn -B -pl services/download-worker test
docker compose --env-file .env.example -f docker-compose.yml config --quiet
docker compose --env-file .env.example -f docker-compose.ghcr.yml config --quiet
```

La prueba de carga reproducible para el perfil completo está documentada en
`tst/load/README.md`. Su ejecución queda deliberadamente fuera de las pruebas de
compilación porque necesita un despliegue ya iniciado y fuentes controladas.
