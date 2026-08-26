# Batch Downloader: instrucciones para agentes

## Arquitectura y flujo
- Monorepo políglota: reactor Maven (`shared/java-contracts`, `core-api`, `download-worker`, `notification-service`, `translation-service`), FastAPI/scheduler en `api/scraper`, servicio Python de embeddings en `services/semantic-service` y React/Vite en `services/webapp/src/main/resources/frontend`.
- `webapp` sirve Nginx en `:3000`; el navegador usa la fachada `/api/v1` de Core. Scraper y Semantic exponen `/internal/v1/**` con `X-Internal-Service-Token`; no los llames directamente desde React.
- MySQL es la autoridad del catálogo y de Core. Alembic (`api/scraper/alembic/versions`) evoluciona catálogo/pipeline; Flyway (`services/core-api/src/main/resources/db/migration`) evoluciona identidad, bundles, jobs y outbox. No cruces propietarios en una migración.
- El scraper separa FastAPI de `scraper-scheduler`: sus etapas persistentes usan leases, reintentos y parada cooperativa. Reutiliza `WinstallClient`, `DownloadValidator` y `safe_http.py`; no dupliques resolución, SSRF o validación de binarios.
- Descargas: Core guarda job+items+outbox → RabbitMQ transporta IDs → el worker resuelve JIT con el scraper, descarga y empaqueta → MinIO guarda `jobs/{jobId}/bundle.zip` → eventos idempotentes actualizan Core → Core responde con `303` firmado usando `MINIO_PUBLIC_ENDPOINT`. Véase `services/download-worker/README.md`.
- Los items terminan por separado; `PACKAGING` cubre el ZIP real y `READY|PARTIAL|MANUAL_ONLY` son descargables. Conserva la alternativa manual: solo crea `.url` para fallos aceptados con página oficial HTTPS pública.
- Core publica `notification.email.requested`; Notification usa inbox idempotente y enruta verificación/reset a Resend y estados de descarga a SMTP (`RoutingNotificationSender`).
- PostgreSQL/pgvector es una proyección semántica reconstruible: el indexador consume `/internal/v1/semantic/documents`; Semantic devuelve candidatos y MySQL conserva filtros, facetas, paginación y orden. Ante índice incompleto/error se degrada toda la petición a búsqueda léxica.
- `docker-compose.yml` construye imágenes locales; `docker-compose.ghcr.yml` consume GHCR. `scripts/compose_health.py` define paridad, dependencias y capacidades (`base`, `downloads`, `semantic`, `notifications`, `translations`, `background`).

## Invariantes y convenciones
- Distingue estado de cola, estado público y fuente resuelta. El catálogo solo persiste `available|review|missing`; `unresolved` es el filtro `review OR missing` y `pending` es interno. `catalog_status`/`catalog_counters` son proyecciones mantenidas por triggers; prueba cambios con MySQL real (`test_catalog_projection_mysql.py`).
- El catálogo es server-side y `operatingSystems` usa semántica OR. Crear un job exige una fuente `VALIDATED`; la antigüedad por sí sola no la invalida porque el worker revalida antes de revelar la URL.
- En Core conserva los límites hexagonales de `downloads` e `identity`: dominio sin frameworks y aplicación sin imports de infraestructura (`ArchitectureRulesTest`).
- Inspección manual y alta desde web oficial entran siempre por Core admin: cifra URLs, encola solo IDs, mantén `preview` sin escrituras y haz que `apply` revalide y publique en una transacción.
- Para cambios HTTP sincroniza `shared/contracts/openapi/batch-downloader-api.yaml`, controladores, clientes/tipos TypeScript y pruebas. Para RabbitMQ sincroniza `shared/contracts/asyncapi/messaging.yaml`, JSON Schemas, productores y consumidores; conserva `eventId`, `schemaVersion`, `correlationId` e inbox/outbox idempotentes.
- Las traducciones viven en `services/translation-service/locales/{template,es}`: archivos homónimos, mismas claves, sin duplicados entre páginas. El frontend las importa mediante `@batch-locales`; ejecuta el build desde la raíz montada.
- No registres URLs resueltas/firmadas, cookies, tokens, prompts/respuestas LLM ni contenido de instaladores. La cookie anónima `BATCH_DOWNLOAD_OWNER` es HttpOnly y las mutaciones de navegador requieren CSRF.
- Añade configuración compartida/secreta a `.env.example`, ajustes no sensibles al `.env.example` del servicio y cableado a ambos Compose. Regenera `.env` ignorados con `scripts/sync-service-env-files.ps1`; no añadas DSN completos como `SCRAPER_DATABASE_URL`.
- Java requiere 25 (Maven 3.9); los módulos con Mockito declaran su propio `mockito.javaagent` en su `pom.xml`. El scraper de producción compila CPython 3.14t con `--disable-gil` y SQLAlchemy sin C extensions; no compartas sesiones/clientes/locks mutables entre hilos.
- Semantic trabaja offline (`local_files_only`, `trust_remote_code=False`, `safetensors`); `semantic-model-worker` es dueño de preparación/benchmark/activación. No descargues o entrenes modelos durante el arranque.

## Verificación focalizada
```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-26 mvn -B verify
.\.venv\Scripts\python.exe -m pytest -q api/scraper
.\.venv\Scripts\python.exe -m pytest -q services/semantic-service
npm --prefix services/webapp/src/main/resources/frontend ci
npm --prefix services/webapp/src/main/resources/frontend run lint
npm --prefix services/webapp/src/main/resources/frontend test -- --run
npm --prefix services/webapp/src/main/resources/frontend run build
npx --yes @redocly/cli@1.34.2 lint shared/contracts/openapi/batch-downloader-api.yaml
npx --yes @asyncapi/cli@3.4.2 validate shared/contracts/asyncapi/messaging.yaml
docker compose --env-file .env.example -f docker-compose.yml --profile "*" config --quiet
docker compose --env-file .env.example -f docker-compose.ghcr.yml --profile "*" config --quiet
python scripts/compose_health.py validate --env-file .env.example
python -m unittest discover -s scripts/tests -v
```
- Replica el job afectado de `.github/workflows/quality.yml`; usa `-Dtest=Clase#método` (entre comillas en PowerShell) o `pytest archivo.py::test` para iterar. La carga k6 de `tst/load/README.md` requiere un despliegue ya iniciado y fuentes controladas; no usa orígenes de terceros.
