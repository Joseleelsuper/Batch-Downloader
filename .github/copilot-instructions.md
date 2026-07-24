# Batch Downloader: instrucciones para agentes

## Arquitectura y flujos
- Monorepo: `services/core-api` (Spring API pública), `api/scraper` (FastAPI + scheduler), `services/download-worker`, `notification-service`, `translation-service`, `semantic-service` y React/Vite bajo `services/webapp/src/main/resources/frontend`.
- Nginx expone `http://localhost:3000` y enruta `/api/*` a Core; el scraper solo se consume mediante `/internal/v1/*` con `X-Internal-Service-Token`.
- Alembic (`api/scraper/alembic`) es dueño de catálogo, fuentes y pipeline; Flyway (`services/core-api/.../db/migration`) es dueño de identidad, bundles, jobs y outbox. No mezcles propietarios en una migración.
- Pipeline persistente: `searcher_filter -> filter_scraper -> scraper_so_filter -> so_filter_descriptor`. Cada etapa usa leases/reintentos; una parada es cooperativa y conserva las colas.
- `SO Filter` proyecta `software_apps.operating_systems_json`; Core lo usa para iconos y filtro OR. La disponibilidad y creación de jobs siguen exigiendo una fuente previamente `VALIDATED`.
- Los iconos GitHub se resuelven dentro del scraper normal; no recrees una cola, worker ni botón administrativo de iconos.
- Descargas: Core persiste job+items+outbox, RabbitMQ transporta solo IDs, el worker pide la URL al scraper (revalidación inmediata), genera ZIP en MinIO y Core entrega un `303` firmado con `MINIO_PUBLIC_ENDPOINT`.
- MySQL sigue siendo la autoridad de filtros, estados, facetas y paginación. PostgreSQL/pgvector es una proyección semántica reconstruible alimentada únicamente por `/internal/v1/semantic/documents`.
- Los textos semánticos solo pueden contener nombre, package ID, editor, tags, descripciones, sistemas, arquitecturas, versión y dominio registrado. No incluyas URLs completas, secretos ni metadatos crudos.

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
- `searchMode` solo admite `lexical` y `hybrid`; omitirlo en la API equivale a `lexical`. Un modo híbrido debe obtener una única colección semántica por petición y degradar la petición completa a literal ante timeout, 401, 5xx, índice incompleto o más de 2.000 candidatos.
- El RRF usa `k=60`. El peso calibrado pertenece a la versión inmutable del modelo y llega desde el servicio semántico; no publiques totales o facetas con un alcance semántico parcial.
- La propiedad anónima de jobs depende de la cookie HttpOnly `BATCH_DOWNLOAD_OWNER`; no pongas tokens en URLs ni relajes CSRF.

## Modelos semánticos y Python
- El baseline inicial es E5-base zero-shot. MiniLM, E5-base y BGE-M3 se registran con revisión HF inmutable, dimensiones y prefijos; el entrenamiento solo se ejecuta mediante `semantic-trainer`, nunca en el arranque.
- La promoción y el rollback son atómicos y requieren cobertura completa del `contentHash`. Conserva el adaptador PEFT, el modelo fusionado para inferencia, dataset, semilla, configuración e informes JSON/CSV/Markdown; no registres un artefacto hasta validar su recarga y dimensión.
- El scraper de producción es CPython 3.14.6 free-threaded compilado con `--disable-gil`. No uses `PYTHON_GIL=0`; el arranque debe comprobar `Py_GIL_DISABLED == 1` y `sys._is_gil_enabled() is False` tras importar extensiones.
- Instala SQLAlchemy desde fuente con `DISABLE_SQLALCHEMY_CEXT=1` en las imágenes 3.14 y 3.14t; su wheel C actual reactiva el GIL y debe hacer fallar el guard.
- No compartas sesiones SQLAlchemy, clientes HTTP, objetos Playwright, eventos, locks ni cachés mutables entre hilos. Solo las funciones puras de parseo, normalización, scoring y deduplicación pasan al executor acotado.

## Invariantes del catálogo
- Separa siempre tres conceptos: estado interno de cola, clasificación pública del catálogo y resolución concreta de una fuente. `pending` pertenece a colas o solicitudes internas, nunca al catálogo público.
- Los únicos estados públicos son `available`, `review` y `missing`, con precedencia en ese orden. Son disjuntos y su suma debe ser exactamente `all` para las aplicaciones activas.
- `software_apps.catalog_status` y la fila singleton `catalog_counters(id=1)` son proyecciones internas mantenidas por triggers MySQL. El código de aplicación no debe escribir sus contadores directamente ni recalcularlos durante una petición.
- Toda escritura en aplicaciones, fuentes o candidatos resueltos debe usar las tablas cubiertas por esos triggers. Al borrar datos, selecciona primero IDs y elimina por clave primaria en lotes; no uses subconsultas sobre una tabla que el trigger de la misma sentencia actualizará.
- MySQL debe arrancar con `log_bin_trust_function_creators=1` cuando Alembic use el usuario de aplicación; conserva esa opción sincronizada en Compose y Testcontainers para que los triggers puedan instalarse con binlog activo.
- La antigüedad de `checked_at` o `expires_at` nunca cambia por sí sola la clasificación. Una fuente `direct`/`fallback` válida con un candidato `catalog_downloadable=1` sigue visible y seleccionable; el scraper la revalida JIT antes de revelar la URL al worker.
- `downloadable=true` significa que se puede crear un trabajo, no que la URL almacenada sea todavía fresca. Un fallo terminal invalida el candidato y deja que los triggers reclasifiquen; un fallo transitorio no cambia el catálogo.
- Búsqueda, conteo, facetas, bundles y selección de fuentes deben compartir esta semántica. `GET /api/v1/apps/stats` solo lee `catalog_counters` por su PK y nunca ejecuta `COUNT`, `SUM` ni joins operativos.
- Toda migración que altere la semántica del catálogo debe incluir backfill, triggers, comprobación de invariantes y modos de mantenimiento `check`/`repair`; ambos deben ser idempotentes y nunca ejecutarse durante una petición web.
- Cualquier cambio de estados exige actualizar juntos Core, Alembic/backfill/triggers, OpenAPI, TypeScript, traducciones y pruebas. Las regresiones deben usar MySQL real y casos multifuente; comprobar únicamente fragmentos de SQL no basta.

## Verificación
```powershell
mvn -B verify
docker compose --env-file .env run --rm scraper-api pytest /app/tests
docker compose --env-file .env run --rm semantic-indexer python -m app.indexer
docker compose --env-file .env --profile training run --rm semantic-trainer
docker compose --env-file .env --profile benchmark up --build --exit-code-from scraper-python314-benchmark-report scraper-python314-benchmark-report
cd services/webapp/src/main/resources/frontend; npm ci; npm test -- --run; npm run build
docker compose --env-file .env config --quiet
docker compose --env-file .env up --build
docker compose --env-file .env ps; docker compose --env-file .env logs --tail 200
```
- Para fallos de jobs revisa, en orden: respuesta `409`, revalidación de `/internal/v1/sources/{id}/resolution`, eventos RabbitMQ, estado del worker y `Location` del `303` (debe resolver desde el navegador, no usar `minio:9000`).
