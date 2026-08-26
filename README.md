# Batch-Downloader

<div align="center">
    <img src="./assets/BatchDownloaderI.png" alt="Batch Downloader" width="400px"/>
</div>

Descarga y empaqueta varios instaladores en un solo trabajo. La interfaz queda disponible en `http://localhost:3000`.

## Índice

- [Inicio rápido](#inicio-rápido)
- [Tests](#tests)
- [Endpoints](#endpoints)
- [Variables de entorno](#variables-de-entorno)
- [Estados de los jobs](#estados-de-los-jobs)
- [Licencia](#licencia)
- [Personas](#personas)

## Inicio rápido

Antes del primer arranque, copia `.env.example` como `.env` y cambia sus credenciales.

### Stack completo

```powershell
docker compose up --build --force-recreate
```

### Un servicio, BBDD o API

```powershell
# Ver los nombres disponibles
docker compose config --services

# Arrancar un componente y sus dependencias
docker compose up --build --force-recreate core-api
docker compose up --build --force-recreate mysql

# Arrancar un solo contenedor
docker compose up --build --force-recreate --no-deps core-api
```

Sustituye `core-api` por cualquier nombre devuelto por `docker compose config --services`; usa `mysql` o `postgres` para las BBDD y `core-api`, `scraper-api` o `semantic-service` para las API.

## Tests

Los comandos asumen que las dependencias Python y Node ya están instaladas. Maven se ejecuta en Docker para no exigir una instalación local.

### Todos los tests

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-26 mvn -B test
.\.venv\Scripts\python.exe -m pytest -q api/scraper
.\.venv\Scripts\python.exe -m pytest -q services/semantic-service
npm --prefix services/webapp/src/main/resources/frontend test -- --run
python -m unittest discover -s scripts/tests -v
```

### Tests de un servicio

| Servicio | Comando |
| --- | --- |
| Java | `docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-26 mvn -B -pl services/core-api -am test` |
| Scraper | `.\.venv\Scripts\python.exe -m pytest -q api/scraper` |
| Semántico | `.\.venv\Scripts\python.exe -m pytest -q services/semantic-service` |
| Frontend | `npm --prefix services/webapp/src/main/resources/frontend test -- --run` |

En el comando Java, cambia `services/core-api` por `services/download-worker`, `services/notification-service` o `services/translation-service`.

### Un solo test

```powershell
# Java: clase#método
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-26 mvn -B -pl services/core-api -am "-Dtest=DownloadJobServiceTest#createsPartialAnonymousJobAndDisablesEmailNotification" "-Dsurefire.failIfNoSpecifiedTests=false" test

# Python: archivo::test
.\.venv\Scripts\python.exe -m pytest -q "api/scraper/tests/test_internal_routes.py::test_internal_resolution_requires_constant_time_service_token"

# Frontend: archivo y nombre
npm --prefix services/webapp/src/main/resources/frontend test -- --run src/api/domainClients.test.ts -t "maps the anonymous 204 response to null"
```

## Endpoints

Permisos: **Público** no requiere sesión; **Propietario** exige la sesión `USER` o la cookie anónima `BATCH_DOWNLOAD_OWNER`; **Interno** exige `X-Internal-Service-Token`; **Operativo** no tiene autenticación de aplicación y debe restringirse por red. Todas las operaciones `POST`, `PUT`, `PATCH` y `DELETE` realizadas desde el navegador requieren además el token obtenido en `GET /api/v1/auth/csrf`; los endpoints internos no usan CSRF.

<details>
<summary><strong>API pública y de usuario</strong></summary>

| Método | Ruta | Permiso | Descripción |
| --- | --- | --- | --- |
| `GET` | `/api/health` | Público | Comprueba la salud básica de Core API. |
| `GET` | `/api/v1/auth/csrf` | Público | Entrega el token CSRF para operaciones mutables. |
| `POST` | `/api/v1/auth/register` | Público | Registra una cuenta. |
| `POST` | `/api/v1/auth/login`, `/api/v1/auth/logout` | Público | Abre o cierra la sesión de usuario. |
| `GET` | `/api/v1/auth/me` | Público | Devuelve la sesión actual o `204`. |
| `POST` | `/api/v1/auth/email-verification/confirm`, `/api/v1/auth/email-verification/resend` | Público | Confirma o reenvía la verificación de correo. |
| `POST` | `/api/v1/auth/password-reset/request`, `/api/v1/auth/password-reset/confirm` | Público | Solicita o confirma un cambio de contraseña. |
| `GET` | `/api/v1/auth/oauth2/google`, `/api/v1/auth/oauth2/authorization/google`, `/api/v1/auth/oauth2/callback/google` | Público | Inicia Google OIDC y procesa su callback. |
| `PATCH` | `/api/v1/auth/preferences` | `USER` | Actualiza la preferencia de notificaciones. |
| `GET`, `PATCH` | `/api/v1/users/me` | `USER` | Consulta o actualiza el perfil propio. |
| `GET` | `/api/v1/users/me/dashboard`, `/api/v1/users/me/downloads` | `USER` | Consulta el resumen y el historial propios. |
| `GET`, `POST` | `/api/v1/users/me/bundles` | `USER` | Lista o crea bundles privados propios. |
| `GET`, `PATCH`, `DELETE` | `/api/v1/users/me/bundles/{bundleId}` | `USER` | Consulta, actualiza o elimina un bundle propio. |
| `GET` | `/api/v1/apps`, `/api/v1/apps/stats`, `/api/v1/apps/facets` | Público | Busca aplicaciones y obtiene estadísticas o facetas. |
| `GET` | `/api/v1/apps/{appId}` | Público | Obtiene el detalle de una aplicación. |
| `GET` | `/api/v1/bundles`, `/api/v1/bundles/{bundleId}` | Público | Lista bundles visibles u obtiene uno visible/propio. |
| `POST` | `/api/v1/software-requests` | Público | Solicita que se añada una aplicación. |
| `POST` | `/api/v1/download-jobs` | Público | Crea un trabajo y asigna su propietario. |
| `GET`, `DELETE` | `/api/v1/download-jobs/{jobId}` | Propietario | Consulta o cancela un trabajo. |
| `GET` | `/api/v1/download-jobs/{jobId}/events` | Propietario | Emite el progreso por SSE. |
| `GET` | `/api/v1/download-jobs/{jobId}/file` | Propietario | Redirige al ZIP firmado. |
| `GET` | `/api/v1/download-jobs/{jobId}/file-link` | Propietario | Devuelve el enlace firmado sin navegar. |
| `GET` | `/api/v1/locales/es` | Público | Devuelve las traducciones en español. |
| `WS` | `/api/v1/catalog/ws` | Público | Notifica cambios del catálogo. |

</details>

<details>
<summary><strong>API administrativa</strong></summary>

Todas estas rutas exigen sesión `ADMIN`, salvo el login.

| Método | Ruta | Permiso | Descripción |
| --- | --- | --- | --- |
| `POST` | `/api/v1/admin/auth/login` | Público | Abre una sesión administrativa. |
| `GET`, `POST` | `/api/v1/admin/auth/me`, `/api/v1/admin/auth/logout` | `ADMIN` | Consulta o cierra la sesión administrativa. |
| `GET` | `/api/v1/admin/requests` | `ADMIN` | Lista solicitudes de software. |
| `GET`, `POST` | `/api/v1/admin/bundles` | `ADMIN` | Lista o crea bundles administrados. |
| `PATCH`, `DELETE` | `/api/v1/admin/bundles/{bundleId}` | `ADMIN` | Actualiza o elimina un bundle administrado. |
| `GET`, `POST`, `DELETE` | `/api/v1/admin/apps` | `ADMIN` | Lista, crea o elimina todo el catálogo de aplicaciones. |
| `GET` | `/api/v1/admin/apps/export.csv` | `ADMIN` | Exporta el catálogo en CSV. |
| `PATCH`, `DELETE` | `/api/v1/admin/apps/{appId}` | `ADMIN` | Actualiza o elimina una aplicación. |
| `PUT` | `/api/v1/admin/apps/{appId}/tags` | `ADMIN` | Reemplaza sus etiquetas. |
| `PATCH` | `/api/v1/admin/apps/{appId}/sources/{sourceId}` | `ADMIN` | Actualiza una fuente de descarga. |
| `POST` | `/api/v1/admin/apps/{appId}/generate-description` | `ADMIN` | Encola la generación de descripción. |
| `GET` | `/api/v1/admin/apps/absence-verifications/summary` | `ADMIN` | Resume ausencias verificadas y pendientes. |
| `GET`, `POST` | `/api/v1/admin/apps/{appId}/absence-verification` | `ADMIN` | Consulta o confirma la ausencia de instaladores. |
| `POST` | `/api/v1/admin/apps/{appId}/manual-installer-inspections` | `ADMIN` | Inicia una inspección manual. |
| `GET` | `/api/v1/admin/apps/{appId}/manual-installer-inspections/current` | `ADMIN` | Recupera la inspección abierta. |
| `GET` | `/api/v1/admin/apps/{appId}/manual-installer-inspections/{inspectionId}` | `ADMIN` | Consulta una inspección. |
| `POST` | `/api/v1/admin/apps/{appId}/manual-installer-inspections/{inspectionId}/apply` | `ADMIN` | Revalida y publica sus resultados. |
| `POST` | `/api/v1/admin/app-discoveries` | `ADMIN` | Analiza una web oficial. |
| `GET`, `POST` | `/api/v1/admin/app-discoveries/{discoveryId}`, `/api/v1/admin/app-discoveries/{discoveryId}/apply` | `ADMIN` | Consulta o aplica un descubrimiento. |
| `GET`, `POST` | `/api/v1/admin/scraper/runs` | `ADMIN` | Lista o crea ejecuciones del scraper. |
| `GET` | `/api/v1/admin/scraper/current`, `/api/v1/admin/scraper/logs`, `/api/v1/admin/scraper/queues`, `/api/v1/admin/scraper/metrics`, `/api/v1/admin/scraper/snapshots`, `/api/v1/admin/scraper/event` | `ADMIN` | Consulta ejecución, logs, colas, métricas, snapshots o el último evento. |
| `POST` | `/api/v1/admin/scraper/queues/recover-stuck`, `/api/v1/admin/scraper/queues/retry-failed`, `/api/v1/admin/scraper/queues/prune-terminal` | `ADMIN` | Recupera, reintenta o limpia elementos de cola. |
| `POST` | `/api/v1/admin/scraper/commands` | `ADMIN` | Envía un comando durable al scraper. |
| `POST` | `/api/v1/admin/scraper/descriptions/enqueue-missing` | `ADMIN` | Encola descripciones ausentes. |
| `GET` | `/api/v1/admin/audit` | `ADMIN` | Consulta la auditoría administrativa. |
| `GET` | `/api/v1/admin/semantic/overview` | `ADMIN` | Resume salud, cobertura, disco y operaciones. |
| `GET` | `/api/v1/admin/semantic/models`, `/api/v1/admin/semantic/models/{modelId}` | `ADMIN` | Lista o consulta modelos. |
| `POST` | `/api/v1/admin/semantic/models/{modelId}/prepare`, `/api/v1/admin/semantic/models/{modelId}/activate` | `ADMIN` | Prepara o activa un modelo. |
| `DELETE` | `/api/v1/admin/semantic/models/{modelId}` | `ADMIN` | Encola la eliminación de un modelo inactivo. |
| `GET`, `POST` | `/api/v1/admin/semantic/benchmarks` | `ADMIN` | Lista o crea benchmarks. |
| `GET` | `/api/v1/admin/semantic/operations`, `/api/v1/admin/semantic/operations/{operationId}` | `ADMIN` | Lista o consulta operaciones duraderas. |
| `DELETE`, `POST` | `/api/v1/admin/semantic/operations/{operationId}`, `/api/v1/admin/semantic/operations/{operationId}/retry` | `ADMIN` | Cancela o reintenta una operación. |
| `WS` | `/api/v1/admin/scraper/ws` | `ADMIN` | Notifica cambios del scraper. |

</details>

<details>
<summary><strong>Endpoints internos y operativos</strong></summary>

| Servicio | Método y ruta | Permiso | Descripción |
| --- | --- | --- | --- |
| Core | `POST /internal/v1/download-jobs/{jobId}/item-metadata` | Interno | Entrega al worker metadatos seguros de items fallidos. |
| Download Worker | `POST /internal/v1/capacity/check` | Interno | Comprueba si existe capacidad temporal para admitir otro job. |
| Scraper | `GET /api/health`, `/api/health/live`, `/api/health/ready` | Operativo | Salud general, liveness y readiness. |
| Scraper | `GET /internal/v1/metrics` | Interno | Expone métricas Prometheus del pool. |
| Scraper | `GET /internal/v1/semantic/documents` | Interno | Pagina documentos para el índice semántico. |
| Scraper | `GET /internal/v1/sources/{sourceRef}/resolution` | Interno | Resuelve una fuente validada para descarga. |
| Scraper | `POST /internal/v1/content/descriptions/enqueue-missing`, `/internal/v1/content/descriptions/generate` | Interno | Encola descripciones o genera una concreta. |
| Scraper | `POST /internal/v1/admin/apps/{appId}/manual-installer-inspections` | Interno | Crea una inspección de instaladores. |
| Scraper | `GET /internal/v1/admin/apps/{appId}/manual-installer-inspections/current`, `/internal/v1/admin/apps/{appId}/manual-installer-inspections/{inspectionId}` | Interno | Recupera la inspección actual o una concreta. |
| Scraper | `POST /internal/v1/admin/apps/{appId}/manual-installer-inspections/{inspectionId}/apply` | Interno | Aplica una inspección revalidada. |
| Scraper | `POST /internal/v1/admin/app-discoveries` | Interno | Inicia el descubrimiento de una aplicación. |
| Scraper | `GET /internal/v1/admin/app-discoveries/{discoveryId}` | Interno | Consulta el descubrimiento. |
| Scraper | `POST /internal/v1/admin/app-discoveries/{discoveryId}/apply` | Interno | Aplica un descubrimiento revalidado. |
| Semántico | `GET /semantic/health`, `/semantic/health/live`, `/semantic/health/ready` | Operativo | Salud general, liveness y readiness. |
| Semántico | `GET /internal/v1/metrics` | Interno | Expone métricas Prometheus. |
| Semántico | `POST /internal/v1/semantic/search` | Interno | Ejecuta la búsqueda semántica. |
| Semántico | `GET /internal/v1/admin/semantic/overview` | Interno | Obtiene el resumen administrativo. |
| Semántico | `GET /internal/v1/admin/semantic/models`, `/internal/v1/admin/semantic/models/{modelId}` | Interno | Lista o consulta modelos. |
| Semántico | `POST /internal/v1/admin/semantic/models/{modelId}/prepare`, `/internal/v1/admin/semantic/models/{modelId}/activate`, `/internal/v1/admin/semantic/models/{modelId}/warm` | Interno | Prepara, activa o calienta un modelo. |
| Semántico | `DELETE /internal/v1/admin/semantic/models/{modelId}` | Interno | Encola la eliminación de un modelo. |
| Semántico | `GET, POST /internal/v1/admin/semantic/benchmarks` | Interno | Lista o crea benchmarks. |
| Semántico | `GET /internal/v1/admin/semantic/operations`, `/internal/v1/admin/semantic/operations/{operationId}` | Interno | Lista o consulta operaciones. |
| Semántico | `DELETE /internal/v1/admin/semantic/operations/{operationId}` | Interno | Solicita la cancelación. |
| Semántico | `POST /internal/v1/admin/semantic/operations/{operationId}/retry` | Interno | Reintenta una operación. |
| Webapp | `GET /healthz` | Público | Comprueba Nginx y el frontend. |
| Java | `GET /actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`, `/actuator/prometheus` | Operativo | Salud, información y métricas de Core, Worker, Notification y Translation. |
| Documentación | `GET /v3/api-docs`, `/swagger-ui/index.html` | Público en Core | OpenAPI y Swagger UI de Core. |
| Documentación | `GET /openapi.json`, `/docs`, `/redoc` | Operativo | OpenAPI, Swagger UI y ReDoc de Scraper y Semántico. |
| Descargas | `GET /{bucket}/jobs/{jobId}/bundle.zip` | URL firmada | Sirve el ZIP desde el host de descargas de MinIO. |

</details>

El contrato público versionado está en [`shared/contracts/openapi/batch-downloader-api.yaml`](shared/contracts/openapi/batch-downloader-api.yaml).

## Variables de entorno

`.env.example` contiene valores compartidos, puertos y secretos; los `.env.example` de cada servicio contienen ajustes no sensibles. Las variantes `.env.scheduler`, `.env.indexer`, `.env.model-worker` y `.env.trainer` repiten la configuración de su servicio con el rol indicado.

<details>
<summary><strong>Globales</strong> — <code>.env.example</code></summary>

| Variables | Uso |
| --- | --- |
| `GHCR_REGISTRY`, `GHCR_OWNER`, `GHCR_IMAGE_PREFIX`, `GHCR_IMAGE_TAG` | Nombre y etiqueta de las imágenes publicadas. |
| `MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_HOST_PORT` | Credenciales, base y puerto host de MySQL. |
| `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_HOST_PORT` | Credenciales, base y puerto host de PostgreSQL/pgvector. |
| `RABBITMQ_DEFAULT_USER`, `RABBITMQ_DEFAULT_PASS`, `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_HOST_PORT`, `RABBITMQ_MANAGEMENT_HOST_PORT`, `RABBITMQ_COMMAND_EXCHANGE`, `RABBITMQ_EVENT_EXCHANGE` | Acceso, puertos y exchanges de RabbitMQ. |
| `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD`, `MINIO_CORE_ACCESS_KEY`, `MINIO_CORE_SECRET_KEY`, `MINIO_WORKER_ACCESS_KEY`, `MINIO_WORKER_SECRET_KEY` | Identidades de administración, lectura y escritura de MinIO. |
| `MINIO_API_HOST_PORT`, `MINIO_CONSOLE_HOST_PORT`, `MINIO_DOWNLOAD_HOST_PORT`, `MINIO_ENDPOINT`, `MINIO_PUBLIC_ENDPOINT`, `MINIO_ZIP_BUCKET`, `MINIO_ZIP_QUOTA`, `MINIO_REGION`, `MINIO_STALE_UPLOADS_EXPIRY`, `MINIO_STALE_UPLOADS_CLEANUP_INTERVAL` | Red, bucket, cuota y limpieza de artefactos. |
| `APP_PUBLIC_BASE_URL`, `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, `RESEND_API_KEY`, `RESEND_FROM_EMAIL`, `NOTIFICATION_TOKEN_ENCRYPTION_KEY`, `SCRAPER_INTERNAL_SERVICE_TOKEN`, `SCRAPER_URL_PROTECTION_SECRET`, `SCRAPER_LLM_GROQ_API_KEY`, `SCRAPER_LLM_DEEPSEEK_API_KEY` | URL pública, integraciones y secretos compartidos. |
| `CORE_API_ADMIN_USERNAME`, `CORE_API_ADMIN_EMAIL`, `CORE_API_ADMIN_PASSWORD`, `CORE_API_DOWNLOAD_OWNER_SECRET` | Bootstrap de administrador y firma de propietarios anónimos. |
| `NOTIFICATION_SERVICE_SMTP_USERNAME`, `NOTIFICATION_SERVICE_SMTP_PASSWORD` | Credenciales SMTP opcionales. |
| `WEBAPP_HOST_PORT`, `CORE_API_HOST_PORT`, `SEMANTIC_SERVICE_HOST_PORT`, `NOTIFICATION_SERVICE_HOST_PORT`, `MAILPIT_SMTP_HOST_PORT`, `MAILPIT_UI_HOST_PORT`, `DOWNLOAD_WORKER_HOST_PORT`, `TRANSLATION_SERVICE_HOST_PORT` | Puertos publicados en el host. |

</details>

<details>
<summary><strong>Scraper</strong> — <code>api/scraper/.env.example</code></summary>

| Variables | Uso |
| --- | --- |
| `SCRAPER_APP_NAME`, `SCRAPER_WINSTALL_BASE_URL`, `SCRAPER_WINSTALL_API_BASE_URL` | Identidad del servicio y orígenes de Winstall. |
| `SCRAPER_REQUEST_TIMEOUT_SECONDS`, `SCRAPER_MAX_REDIRECTS`, `SCRAPER_MAX_DOWNLOAD_SIZE_BYTES`, `SCRAPER_ICON_MAX_BYTES`, `SCRAPER_ALLOWED_DOWNLOAD_SCHEMES`, `SCRAPER_PLAYWRIGHT_TIMEOUT_MS` | Límites de red, descargas, iconos y navegador. |
| `SCRAPER_MANUAL_INSPECTION_TTL_HOURS`, `SCRAPER_MANUAL_INSPECTION_MAX_ATTEMPTS`, `SCRAPER_MANUAL_PAGE_MAX_BYTES` | Caducidad y límites de inspecciones manuales. |
| `SCRAPER_SO_FILTER_CONCURRENCY`, `SCRAPER_SO_FILTER_MAX_ATTEMPTS` | Concurrencia y reintentos del filtro por sistema operativo. |
| `SCRAPER_SCRAPE_CONCURRENCY`, `SCRAPER_CPU_THREAD_WORKERS`, `SCRAPER_SCHEDULER_TIMEZONE`, `SCRAPER_SCHEDULER_HOUR`, `SCRAPER_SCHEDULER_MINUTE`, `SCRAPER_RUN_ON_STARTUP`, `SCRAPER_SCRAPE_MAX_APPS`, `SCRAPER_SCRAPE_APP_TIMEOUT_SECONDS` | Workers, horario y alcance de cada barrido. |
| `SCRAPER_WORKER_HEARTBEAT_INTERVAL_SECONDS`, `SCRAPER_WORKER_HEARTBEAT_STALE_SECONDS`, `SCRAPER_WORKER_FAILURE_THRESHOLD` | Salud de scheduler y workers. |
| `SCRAPER_LLM_GROQ_BASE_URL`, `SCRAPER_LLM_GROQ_MODEL`, `SCRAPER_LLM_GROQ_FALLBACK_MODELS`, `SCRAPER_LLM_DEEPSEEK_BASE_URL`, `SCRAPER_LLM_DEEPSEEK_MODEL`, `SCRAPER_LLM_MAX_CONCURRENCY`, `SCRAPER_LLM_MAX_APPS_PER_RUN`, `SCRAPER_LLM_REQUEST_TIMEOUT_SECONDS` | Proveedores, modelos y límites de generación. |
| `SCRAPER_DATABASE_POOL_MAX`, `SCRAPER_DATABASE_MAX_OVERFLOW`, `SCRAPER_DATABASE_POOL_TIMEOUT_SECONDS`, `SCRAPER_DATABASE_POOL_RECYCLE_SECONDS` | Pool MySQL. La variante scheduler usa un timeout propio. |

</details>

<details>
<summary><strong>Core API</strong> — <code>services/core-api/.env.example</code></summary>

| Variables | Uso |
| --- | --- |
| `CORE_API_SERVER_PORT`, `CORE_API_SCRAPER_API_URL`, `CORE_API_SEMANTIC_SERVICE_URL`, `CORE_API_DOWNLOAD_WORKER_URL`, `CORE_API_DOWNLOAD_WORKER_CAPACITY_TIMEOUT`, `CORE_API_SEMANTIC_REQUEST_TIMEOUT`, `CORE_API_SEMANTIC_ADMIN_REQUEST_TIMEOUT` | Puerto, servicios internos y timeouts HTTP. |
| `CORE_API_BCRYPT_STRENGTH`, `CORE_API_DB_POOL_MIN`, `CORE_API_DB_POOL_MAX`, `CORE_API_DB_POOL_TIMEOUT`, `CORE_API_AUTH_HASH_CONCURRENCY`, `CORE_API_AUTH_HASH_QUEUE`, `CORE_API_AUTH_HASH_WAIT` | Coste de hash, pool MySQL y admisión de autenticación. |
| `CORE_API_AUTH_LOGIN_MAX_PER_MINUTE`, `CORE_API_AUTH_REGISTER_MAX_PER_HOUR`, `CORE_API_AUTH_RESET_MAX_PER_HOUR`, `CORE_API_AUTH_VERIFICATION_RESEND_MAX_PER_HOUR`, `CORE_API_SESSION_TIMEOUT`, `CORE_API_VERIFICATION_TTL`, `CORE_API_PASSWORD_RESET_TTL` | Rate limits y caducidad de sesiones/tokens. |
| `CORE_API_OUTBOX_DELAY`, `CORE_API_OUTBOX_CLAIM_LEASE`, `CORE_API_OUTBOX_CONFIRM_TIMEOUT`, `CORE_API_RETENTION_INTERVAL`, `CORE_API_REQUIRE_HTTPS`, `CORE_API_COOKIE_SECURE` | Outbox, retención y seguridad HTTP/cookies. |
| `DOWNLOAD_MAX_APPS`, `DOWNLOAD_ZIP_RETENTION`, `DOWNLOAD_PRESIGNED_URL_TTL`, `DOWNLOAD_ANONYMOUS_MAX_ACTIVE_JOBS`, `DOWNLOAD_ANONYMOUS_MAX_CREATES_PER_HOUR`, `DOWNLOAD_ANONYMOUS_MAX_CREATES_PER_IP_HOUR`, `DOWNLOAD_AUTHENTICATED_MAX_ACTIVE_JOBS`, `DOWNLOAD_GLOBAL_MAX_PENDING_JOBS`, `DOWNLOAD_SSE_HEARTBEAT` | Cuotas, retención y eventos de trabajos de descarga. |
| `CORE_API_CATALOG_CACHE_MAXIMUM_SIZE`, `CORE_API_CATALOG_CACHE_TTL`, `CORE_API_DOWNLOAD_EVENTS_QUEUE` | Caché de catálogo y cola de eventos. |

</details>

<details>
<summary><strong>Download Worker</strong> — <code>services/download-worker/.env.example</code></summary>

| Variables | Uso |
| --- | --- |
| `DOWNLOAD_WORKER_SERVER_PORT`, `DOWNLOAD_WORKER_INBOX_URL`, `DOWNLOAD_WORKER_INBOX_USERNAME` | Puerto e inbox idempotente H2. |
| `DOWNLOAD_WORKER_COMMAND_EXCHANGE`, `DOWNLOAD_WORKER_EVENT_EXCHANGE`, `DOWNLOAD_WORKER_INPUT_ROUTING_KEY`, `DOWNLOAD_WORKER_INPUT_QUEUE`, `DOWNLOAD_WORKER_CANCELLATION_ROUTING_KEY`, `DOWNLOAD_WORKER_CANCELLATION_QUEUE`, `DOWNLOAD_WORKER_DLX`, `DOWNLOAD_WORKER_DLQ`, `DOWNLOAD_WORKER_CAPACITY_WAIT_QUEUE`, `DOWNLOAD_WORKER_SOURCE_RESOLVER_URL`, `DOWNLOAD_WORKER_CORE_API_URL` | Topología RabbitMQ y servicios internos. |
| `DOWNLOAD_WORKER_CAPACITY_WAIT_DELAY`, `DOWNLOAD_WORKER_CANCELLATION_CONCURRENCY`, `DOWNLOAD_WORKER_RETRY_ATTEMPTS`, `DOWNLOAD_WORKER_RETRY_INITIAL_INTERVAL`, `DOWNLOAD_WORKER_RETRY_MULTIPLIER`, `DOWNLOAD_WORKER_RETRY_MAX_INTERVAL` | Espera de capacidad, cancelaciones y reintentos. |
| `DOWNLOAD_WORKER_ARTIFACT_RETENTION`, `DOWNLOAD_WORKER_SOURCE_RESOLVER_TIMEOUT`, `DOWNLOAD_WORKER_CORE_API_TIMEOUT` | Retención y timeouts internos. |
| `DOWNLOAD_WORKER_MAX_ITEMS`, `DOWNLOAD_WORKER_MAX_FILE_SIZE`, `DOWNLOAD_WORKER_MAX_TOTAL_SIZE`, `DOWNLOAD_WORKER_MAX_REDIRECTS`, `DOWNLOAD_WORKER_CONNECT_TIMEOUT`, `DOWNLOAD_WORKER_REQUEST_TIMEOUT` | Límites de cada descarga. |
| `DOWNLOAD_WORKER_CONCURRENCY`, `DOWNLOAD_WORKER_JOB_CONCURRENCY`, `DOWNLOAD_WORKER_PER_JOB_CONCURRENCY`, `DOWNLOAD_WORKER_PACKAGING_CONCURRENCY`, `DOWNLOAD_WORKER_ZIP_LEVEL` | Paralelismo global, por job y de empaquetado. |
| `DOWNLOAD_WORKER_MIN_FREE_SPACE`, `DOWNLOAD_WORKER_LARGE_JOB_THRESHOLD`, `DOWNLOAD_WORKER_MULTIPART_PART_SIZE`, `DOWNLOAD_WORKER_TEMP_DIRECTORY` | Reserva de disco, jobs grandes, subida multipart y temporal. |
| `DOWNLOAD_WORKER_INBOX_LEASE`, `DOWNLOAD_WORKER_RETENTION_INTERVAL`, `DOWNLOAD_WORKER_HEARTBEAT_INTERVAL`, `DOWNLOAD_WORKER_HEARTBEAT_STALE_AFTER` | Lease, limpieza y salud del worker. |

</details>

<details>
<summary><strong>Notification Service</strong> — <code>services/notification-service/.env.example</code></summary>

| Variables | Uso |
| --- | --- |
| `NOTIFICATION_SERVICE_SERVER_PORT`, `NOTIFICATION_RETRY_MAX_ATTEMPTS`, `NOTIFICATION_RETRY_INITIAL_INTERVAL`, `NOTIFICATION_RETRY_MULTIPLIER`, `NOTIFICATION_RETRY_MAX_INTERVAL` | Puerto y política de reintentos. |
| `NOTIFICATION_SERVICE_INBOX_URL`, `NOTIFICATION_SERVICE_INBOX_USERNAME`, `NOTIFICATION_SERVICE_INBOX_LEASE_DURATION`, `NOTIFICATION_SERVICE_RETENTION_INTERVAL`, `NOTIFICATION_SERVICE_HEARTBEAT_INTERVAL`, `NOTIFICATION_SERVICE_HEARTBEAT_STALE_AFTER` | Inbox H2, lease, limpieza y salud. |
| `NOTIFICATION_SERVICE_SMTP_HOST`, `NOTIFICATION_SERVICE_SMTP_PORT`, `NOTIFICATION_SERVICE_SMTP_AUTH`, `NOTIFICATION_SERVICE_SMTP_STARTTLS`, `NOTIFICATION_SERVICE_SMTP_CONNECTION_TIMEOUT_MS`, `NOTIFICATION_SERVICE_SMTP_TIMEOUT_MS`, `NOTIFICATION_SERVICE_SMTP_WRITE_TIMEOUT_MS`, `NOTIFICATION_SERVICE_MAIL_FROM`, `NOTIFICATION_SERVICE_MAIL_ZONE_ID` | Transporte y remitente SMTP. |
| `NOTIFICATION_SERVICE_RESEND_BASE_URL`, `NOTIFICATION_SERVICE_RESEND_CONNECT_TIMEOUT`, `NOTIFICATION_SERVICE_RESEND_REQUEST_TIMEOUT` | Endpoint y timeouts de Resend. |

</details>

<details>
<summary><strong>Semantic Service</strong> — <code>services/semantic-service/.env.example</code></summary>

| Variables | Uso |
| --- | --- |
| `SEMANTIC_DEVICE`, `SEMANTIC_INITIAL_MODEL_VERSION`, `SEMANTIC_CANDIDATE_LIMIT`, `SEMANTIC_MINIMUM_SIMILARITY`, `SEMANTIC_INDEX_BATCH_SIZE`, `SEMANTIC_INDEX_INTERVAL_SECONDS`, `SEMANTIC_INDEX_LEASE_SECONDS`, `SEMANTIC_SEARCH_TIMEOUT_SECONDS` | Modelo, búsqueda e indexación. |
| `SEMANTIC_API_DB_POOL_MIN`, `SEMANTIC_API_DB_POOL_MAX`, `SEMANTIC_INDEXER_DB_POOL_MIN`, `SEMANTIC_INDEXER_DB_POOL_MAX`, `SEMANTIC_MODEL_WORKER_DB_POOL_MIN`, `SEMANTIC_MODEL_WORKER_DB_POOL_MAX`, `SEMANTIC_DB_POOL_TIMEOUT_SECONDS`, `SEMANTIC_DB_POOL_MAX_LIFETIME_SECONDS` | Pools PostgreSQL por proceso. |
| `SEMANTIC_SEARCH_CONCURRENCY`, `SEMANTIC_SEARCH_CAPACITY_WAIT_SECONDS`, `SEMANTIC_BACKGROUND_TIMEZONE`, `SEMANTIC_BACKGROUND_START_HOUR`, `SEMANTIC_BACKGROUND_END_HOUR`, `SEMANTIC_OPERATION_POLL_SECONDS`, `SEMANTIC_OPERATION_LEASE_SECONDS`, `SEMANTIC_RETENTION_INTERVAL_SECONDS` | Admisión, ventana de fondo, operaciones y limpieza. |
| `SEMANTIC_WORKER_HEARTBEAT_INTERVAL_SECONDS`, `SEMANTIC_WORKER_HEARTBEAT_STALE_SECONDS`, `SEMANTIC_WORKER_FAILURE_THRESHOLD`, `SEMANTIC_MODEL_MAX_BYTES`, `SEMANTIC_MODEL_MIN_FREE_BYTES` | Salud y cuotas de modelos. |
| `SEMANTIC_TRAINER_SEED`, `SEMANTIC_TRAINER_EPOCHS`, `SEMANTIC_TRAINER_BATCH_SIZE`, `SEMANTIC_TRAINER_MAX_STEPS`, `SEMANTIC_TRAINER_MODELS` | Reproducibilidad y alcance del entrenamiento. |
| `SEMANTIC_DATABASE_ROLE` | Rol de base de datos: `api`, `indexer` o `model_worker`. |

</details>

<details>
<summary><strong>Translation y Frontend</strong></summary>

| Servicio | Variables | Uso |
| --- | --- | --- |
| Translation | `TRANSLATION_SERVICE_SERVER_PORT`, `TRANSLATION_SERVICE_LOCALES_PATH`, `TRANSLATION_SERVICE_CACHE_MAX_AGE` | Puerto, directorio de traducciones y caché HTTP. |
| Frontend | `VITE_API_BASE_URL` | Base opcional de la API; vacía usa el mismo origen. |

</details>

Para regenerar los `.env` locales no sensibles desde las plantillas:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/sync-service-env-files.ps1
```

## Estados de los jobs

### Trabajo de descarga

| Estado | Terminal | Descargable | Descripción |
| --- | :---: | :---: | --- |
| `QUEUED` | No | No | Aceptado y esperando worker o capacidad. |
| `RESOLVING` | No | No | Resolviendo fuentes verificadas. |
| `DOWNLOADING` | No | No | Descargando y validando instaladores. |
| `PACKAGING` | No | No | Generando y subiendo el ZIP. |
| `READY` | Sí | Sí | Todos los instaladores están disponibles. |
| `PARTIAL` | Sí | Sí | El ZIP existe, pero algún item falló o requiere descarga manual. |
| `MANUAL_ONLY` | Sí | Sí | El ZIP contiene solamente accesos a descargas manuales. |
| `FAILED` | Sí | No | El trabajo terminó sin un artefacto utilizable. |
| `CANCELLED` | Sí | No | El propietario canceló el trabajo. |
| `EXPIRED` | Sí | No | El artefacto superó su retención. |

### Item de descarga

| Estado | Descripción |
| --- | --- |
| `QUEUED` | Espera procesamiento. |
| `RESOLVING` | Busca la fuente del instalador. |
| `DOWNLOADING` | Transfiere y valida el archivo. |
| `COMPLETED` | Archivo listo para empaquetar. |
| `FAILED` | No pudo completarse. |
| `CANCELLED` | Se canceló con el trabajo. |

## Licencia

Este proyecto está bajo la Licencia GNU GENERAL PUBLIC LICENSE 3.0. Para más detalles, consulte el archivo [LICENSE](LICENSE).

## Personas

### Autor

<table>
    <tr>
        <td align="center">
            <a href="https://joseleelportfolio.vercel.app/">
                <img src="https://github.com/Joseleelsuper.png" width="100px;" alt="José Gallardo"/>
                <br />
                <sub><b>José Gallardo Caballero</b></sub>
            </a>
        </td>
    </tr>
</table>

### Tutores

<table>
    <tr>
        <td align="center">
            <a href="https://github.com/JoseManuelAroca">
                <img src="https://github.com/JoseManuelAroca.png" width="100px;" alt="José Manuel Aroca Fernández"/>
                <br />
                <sub><b>José Manuel Aroca Fernández</b></sub>
            </a>
        </td>
        <td align="center">
            <a href="https://github.com/RodrigoPascual">
                <img src="https://github.com/RodrigoPascual.png" width="100px;" alt="Rodrigo Pascual García"/>
                <br />
                <sub><b>Rodrigo Pascual García</b></sub>
            </a>
        </td>
    </tr>
</table>

---

Volver al [índice](#Índice).
