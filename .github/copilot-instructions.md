# Batch Downloader: instrucciones para agentes

## Arquitectura y flujos
- Monorepo: `services/core-api` (Spring API pública), `api/scraper` (FastAPI + scheduler), `services/download-worker`, `notification-service`, `translation-service`, `semantic-service` y React/Vite bajo `services/webapp/src/main/resources/frontend`.
- Nginx expone `http://localhost:3000` y enruta `/api/*` a Core; el scraper solo se consume mediante `/internal/v1/*` con `X-Internal-Service-Token`.
- Alembic (`api/scraper/alembic`) es dueño de catálogo, fuentes y pipeline; Flyway (`services/core-api/.../db/migration`) es dueño de identidad, bundles, jobs y outbox. No mezcles propietarios en una migración.
- Pipeline persistente: `searcher_filter -> filter_scraper -> scraper_so_filter -> so_filter_descriptor`. Cada etapa usa leases/reintentos; una parada es cooperativa y conserva las colas.
- `SO Filter` proyecta `software_apps.operating_systems_json`; Core lo usa para iconos y filtro OR. La disponibilidad y creación de jobs siguen exigiendo una fuente previamente `VALIDATED`.
- Los iconos GitHub se resuelven dentro del scraper normal; no recrees una cola, worker ni botón administrativo de iconos.
- Descargas: Core persiste job+items+outbox, RabbitMQ transporta solo IDs, el worker pide la URL al scraper (revalidación inmediata), genera ZIP en MinIO y Core entrega un `303` firmado con `MINIO_PUBLIC_ENDPOINT`.

## Configuración
- En Spring usa solo `${VAR}`: nunca `${VAR:default}`. Toda variable requerida debe existir en `.env` y `.env.example` y ser inyectada explícitamente por ambos Compose.
- No guardes DSN completos como `SCRAPPER_DATABASE_URL` en `.env`: contienen caracteres reservados y Compose intenta expandir `$...`.
- Construye URLs en código. El scraper usa `SQLAlchemy URL.create` en `app/core/config.py`; Compose fija host/puerto internos y toma `MYSQL_USER`, `MYSQL_PASSWORD` y `MYSQL_DATABASE` de `.env`.
- Reutiliza las credenciales canónicas `MYSQL_*`, `RABBITMQ_DEFAULT_*` y `MINIO_ROOT_*`; no crees copias por servicio que puedan divergir.
- Cita con comillas simples cualquier valor `.env` que contenga `$`. Valida siempre con `docker compose --env-file .env config --quiet`.
- Añade nuevas variables a `.env.example` sin secretos y a `.env` solo para la ejecución local solicitada.

## Convenciones de implementación
- Mantén `/api/v1` coordinado entre controladores, `shared/contracts/openapi`, tipos TypeScript y traducciones `es.json`/`template.json` (deben tener exactamente las mismas claves).
- En el scraper conserva `WinstallClient` (API, `__NEXT_DATA__`, HTML), `DownloadValidator` para DNS/IP/redirecciones/MIME/tamaño y URLs cifradas mediante `SCRAPPER_URL_PROTECTION_SECRET`.
- Nunca registres URLs firmadas o resueltas, cookies, tokens, prompts/respuestas LLM ni contenido de instaladores.
- El catálogo es server-side: filtros, facetas, paginación y ranking viven en Core; evita una consulta por fila y conserva la semántica OR de `operatingSystems`.
- La propiedad anónima de jobs depende de la cookie HttpOnly `BATCH_DOWNLOAD_OWNER`; no pongas tokens en URLs ni relajes CSRF.

## Verificación
```powershell
mvn -B verify
docker compose --env-file .env run --rm scraper-api pytest /app/tests
cd services/webapp/src/main/resources/frontend; npm ci; npm test -- --run; npm run build
docker compose --env-file .env config --quiet
docker compose --env-file .env up --build
docker compose --env-file .env ps; docker compose --env-file .env logs --tail 200
```
- Para fallos de jobs revisa, en orden: respuesta `409`, revalidación de `/internal/v1/sources/{id}/resolution`, eventos RabbitMQ, estado del worker y `Location` del `303` (debe resolver desde el navegador, no usar `minio:9000`).
