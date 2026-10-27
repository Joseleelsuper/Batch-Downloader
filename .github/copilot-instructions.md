# Batch Downloader: instrucciones para agentes

## Ramas y pull requests

- Crea las ramas de trabajo desde `development` y dirige todos sus PR a `development`, nunca directamente a `main`.
- La promoción a `main` se realiza únicamente mediante un PR de `development` a `main`, cuando el usuario lo solicite.

## Arquitectura y flujo

- Monorepo políglota: reactor Maven (`shared/java-contracts`, `core-api`, `download-worker`, `notification-service`, `translation-service`), FastAPI/scheduler en `api/scraper`, servicio Python de embeddings en `services/semantic-service` y React/Vite en `services/webapp/src/main/resources/frontend`.
- `webapp` sirve React con Nginx; `/api/v1` pasa por Core salvo `/api/v1/locales/**`, dirigido a Translation (`services/webapp/nginx.conf`). En desarrollo, Vite usa `:5173` y proxy `/api` a Core `:8081`. Scraper y Semantic requieren `X-Internal-Service-Token` bajo `/internal/v1/**`; no los llames desde React.
- MySQL es la autoridad del catálogo y de Core. Alembic (`api/scraper/alembic/versions`) evoluciona catálogo/pipeline; Flyway (`services/core-api/src/main/resources/db/migration`) evoluciona identidad, bundles, jobs y outbox. No cruces propietarios en una migración.
- El scraper separa FastAPI de `scraper-scheduler`: sus etapas persistentes usan leases, reintentos y parada cooperativa. Reutiliza `WinstallClient`, `DownloadValidator` y `safe_http.py`; no dupliques resolución, SSRF o validación de binarios.
- Descargas: Core guarda job+items en FIFO y reserva su pico dentro de 10 GiB antes de publicar al outbox → RabbitMQ transporta IDs → el worker resuelve JIT, descarga y empaqueta → MinIO guarda `jobs/{jobId}/bundle.zip` → eventos idempotentes actualizan Core → Core entrega streaming `200/206` autorizado. La reserva solo se libera tras confirmar el borrado. Véase `services/download-worker/README.md`.
- Los items terminan por separado; `PACKAGING` cubre el ZIP real y `READY|PARTIAL|MANUAL_ONLY` son descargables. Conserva la alternativa manual: solo crea `.url` para fallos aceptados con página oficial HTTPS pública.
- Las cuentas `USER` acceden por magic link; `ADMIN` conserva contraseña (`src/api/account.ts` del frontend). Core publica `notification.email.requested`; Notification consume con inbox idempotente y entrega solo `MAGIC_LINK` a Resend (`RoutingNotificationSender`).
- PostgreSQL/pgvector es una proyección reconstruible. `services/semantic-service/app/indexer.py` sondea documentos y finales del Scraper cada 300 s, también fuera de la ventana nocturna; `bundle_indexer.py` sincroniza metadatos de bundles públicos/oficiales desde Core por revisión. Conserva resultados semánticos con cobertura parcial, sin mezclarlos con coincidencias léxicas; MySQL aplica filtros, facetas, paginación y orden. Ausencia de vectores, errores o truncamiento activan respaldo léxico; indexaciones incompletas no avanzan el checkpoint.
- `docker-compose.yml` construye imágenes locales; `docker-compose.ghcr.yml` consume GHCR. `scripts/compose_health.py` define paridad, dependencias y capacidades (`base`, `downloads`, `semantic`, `notifications`, `translations`, `background`).

## Invariantes y convenciones

- Distingue estado de cola, estado público y fuente resuelta. El catálogo solo persiste `available|review|missing`; `unresolved` es el filtro `review OR missing` y `pending` es interno. `catalog_status`/`catalog_counters` son proyecciones mantenidas por triggers; prueba cambios con MySQL real (`test_catalog_projection_mysql.py`).
- El catálogo es server-side y `operatingSystems` usa OR. Las descargas automáticas requieren fuente `VALIDATED`, sin caducar solo por antigüedad y revalidada por el worker; un job también admite items manuales con página oficial segura (`DownloadJobServiceTest.createsManualItemsForAppsWithoutVerifiedInstaller`). Conserva `sourceRef` individual y `sourceSelections` por aplicación; una elección exacta no disponible produce `selected_source_unavailable`.
- En Core conserva los límites hexagonales de `downloads` e `identity`: dominio sin frameworks y aplicación sin imports de infraestructura (`ArchitectureRulesTest`).
- Inspección manual y alta desde web oficial entran siempre por Core admin: cifra URLs, encola solo IDs, mantén `preview` sin escrituras y haz que `apply` revalide y publique en una transacción.
- Para cambios HTTP sincroniza `shared/contracts/openapi/batch-downloader-api.yaml`, controladores, clientes/tipos TypeScript y pruebas. Para RabbitMQ sincroniza `shared/contracts/asyncapi/messaging.yaml`, JSON Schemas, productores y consumidores; conserva `eventId`, `schemaVersion`, `correlationId` e inbox/outbox idempotentes.
- Las traducciones viven en `services/translation-service/locales/{template,es,en}`: archivos homónimos, mismas claves, sin duplicados entre páginas. Vite las importa con `@batch-locales`: conserva el acceso al monorepo al montar el frontend. Maven genera `services/translation-service/target/compiled-locales`; Translation consume ese resultado.
- Reutiliza `requestJson` de `src/api/http/client.ts` del frontend para credenciales, CSRF y errores; `GET /api/v1/auth/csrf` prepara las mutaciones. La cookie anónima `BATCH_DOWNLOAD_OWNER` es HttpOnly. No registres URLs resueltas/firmadas, cookies, tokens, prompts/respuestas LLM ni contenido de instaladores.
- Añade configuración compartida/secreta a `.env.example`, ajustes no sensibles al `.env.example` del servicio y cableado a ambos Compose. Regenera `.env` ignorados con `scripts/sync-service-env-files.ps1`; no añadas DSN completos como `SCRAPER_DATABASE_URL`.
- Java requiere 25/Maven 3.9; conserva los scripts `scripts/java/install-temurin.sh` y `distrust-emudhra.sh` de los builds. Cada módulo Mockito declara su `mockito.javaagent`. El scraper de producción compila CPython 3.14t con `--disable-gil` y SQLAlchemy sin C extensions; no compartas sesiones/clientes/locks mutables entre hilos.
- Semantic trabaja offline (`local_files_only`, `trust_remote_code=False`, `safetensors`) con un único modelo en `/models/current` y manifiesto obligatorio `batch-model.json`; cambiarlo requiere reiniciar los servicios semánticos. Sus SQL en `services/semantic-service/migrations` se aplican al arrancar, con checksum y advisory lock (`app/database.py`).

## Verificación focalizada

- Desde la raíz, con dependencias Python instaladas (`api/scraper/requirements-dev.txt` y `services/semantic-service[test]`) y Node 24; replica solo el job afectado de [quality.yml](workflows/quality.yml).
```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace maven:3.9.16-eclipse-temurin-25 sh -c 'sh scripts/java/install-temurin.sh && sh scripts/java/distrust-emudhra.sh mvn "$@"' -- -B verify
.\.venv\Scripts\python.exe -m pytest -q api/scraper
.\.venv\Scripts\python.exe -m pytest -q services/semantic-service
npm --prefix services/webapp/src/main/resources/frontend ci
npm --prefix services/webapp/src/main/resources/frontend run typecheck
npm --prefix services/webapp/src/main/resources/frontend run lint
npm --prefix services/webapp/src/main/resources/frontend test -- --run
npm --prefix services/webapp/src/main/resources/frontend run build
```
- Java focalizado: añade `-pl services/core-api -am "-Dtest=Clase#método" "-Dsurefire.failIfNoSpecifiedTests=false"` al comando Maven. MySQL/pgvector usan Testcontainers y requieren acceso al daemon Docker; comprueba qué pruebas se omiten.
- Python: ejecuta cada suite por separado (ambos servicios se llaman `app`); usa `pytest archivo.py::test`. Desde cada servicio, CI ejecuta `ruff check .` y `mypy app` (scraper añade `--ignore-missing-imports`); los cambios de concurrencia también pasan el job `scraper-free-threaded`.
- Contratos: `python -m unittest discover -s scripts/quality/tests -v` requiere JDK y PyYAML; valida además con `npx --yes @redocly/cli@1.34.2 lint shared/contracts/openapi/batch-downloader-api.yaml` y `npx --yes @asyncapi/cli@3.4.2 validate shared/contracts/asyncapi/messaging.yaml`.
- Compose: prepara los `.env` de servicio como el job `compose`; valida `docker compose --env-file .env.example -f docker-compose.yml --profile "*" config --quiet` también con `docker-compose.ghcr.yml`, `python scripts/compose_health.py validate --env-file .env.example` y `python -m unittest discover -s scripts/tests -v`; despliegue añade `scripts/deployment/tests`. Diagnostica el stack con `python scripts/compose_health.py status --require downloads`.
- Desde el frontend, `npm run test:download-flow` crea un proyecto Compose aislado; revisa su job de CI antes de ejecutarla. La carga k6 de `tst/load/README.md` requiere un despliegue ya iniciado y fuentes controladas.
