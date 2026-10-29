# Batch Downloader Scraper

Servicio interno Python para sincronizar el catalogo de Winstall, resolver instaladores
desde webs oficiales y exponer endpoints MVP de busqueda/descarga.

## Comandos

```bash
pip install -r requirements-dev.txt
playwright install chromium
alembic upgrade head
uvicorn app.main:app
python -m app.worker scrape-once
python -m app.worker scheduler
pytest
```

El scheduler debe ejecutarse como proceso separado de FastAPI para evitar trabajos
duplicados cuando existan varias replicas.

## Inspecciones manuales de instaladores

Core es la única fachada para el navegador. Los endpoints equivalentes del
scraper viven bajo
`/internal/v1/admin/apps/{appId}/manual-installer-inspections` y requieren
`X-Internal-Service-Token`. Las URLs del instalador y de su página se cifran en
`manual_installer_inspections`; `result_json`, respuestas y
`manual_installer_enrichment.payload_json` nunca incluyen la URL final.

El scheduler mantiene un único consumidor de esa cola con lease, reintentos y
backoff. Una inspección caduca por defecto a las 24 horas. Página, icono e IA
pueden producir avisos; un binario sin HTTPS público, firma coherente o
confianza `validated` siempre bloquea la publicación. `apply` vuelve a validar
el artefacto y comprueba `expectedAppVersion` antes de escribir metadatos,
fuente y candidato dentro de una sola transacción.

Configuración: `SCRAPER_MANUAL_INSPECTION_TTL_HOURS`,
`SCRAPER_MANUAL_INSPECTION_MAX_ATTEMPTS` y
`SCRAPER_MANUAL_PAGE_MAX_BYTES`.

## Generacion de descripciones

Cada petición a la IA devuelve una descripción larga en español y otra en inglés.
El trabajo solo se completa cuando ambas son válidas. Las aplicaciones antiguas sin
versión inglesa se pueden reencolar desde la administración del catálogo.

La generación usa los proveedores configurados en este orden:

1. OpenAI: `gpt-6-luna`, mediante Responses API.
2. DeepSeek: `deepseek-flash`, mediante Chat Completions.
3. Groq: `openai/gpt-oss-120b`, `qwen/qwen3.8-27b` y `openai/gpt-oss-20b`.

La clave opcional `SCRAPER_LLM_OPENAI_API_KEY` activa OpenAI. Su URL y modelo se
configuran con `SCRAPER_LLM_OPENAI_BASE_URL` y `SCRAPER_LLM_OPENAI_MODEL`.
En Compose, declara las tres variables en el `.env` raíz; la clave puede quedar
vacía para desactivar OpenAI. DeepSeek y Groq mantienen sus variables `SCRAPER_LLM_DEEPSEEK_*` y
`SCRAPER_LLM_GROQ_*`; los fallbacks Groq aceptan un array JSON en
`SCRAPER_LLM_GROQ_FALLBACK_MODELS`. Al actualizar un despliegue existente, sustituye
sus valores de modelo antiguos: las variables configuradas prevalecen sobre los defaults.
No se crean claves ni se realizan regeneraciones durante la configuración.

No hay búsquedas web ni consultas adicionales a páginas durante la generación.
Los modelos utilizan sus conocimientos y el contexto recibido del catálogo o de
las inspecciones. El prompt evita conjeturas y permite textos breves sin relleno.
Si la aplicación no puede identificarse, el proveedor devuelve
`{"error":"insufficient_evidence"}` y se intenta la siguiente alternativa.

Cuotas, errores de autenticación, red, modelos incompatibles o respuestas inválidas
permiten continuar con el siguiente proveedor/modelo. Los cooldowns respetan
`Retry-After`, `X-RateLimit-Reset-*` y las variables existentes
`SCRAPER_LLM_RATE_LIMIT_COOLDOWN_SECONDS`, `SCRAPER_LLM_TRANSIENT_COOLDOWN_SECONDS`
y `SCRAPER_LLM_MODEL_ERROR_COOLDOWN_SECONDS`.

Administración → Scraper conserva «Encolar sin descripción» y permite regenerar
textos de IA, manuales o todos. «Manuales» se refiere a la autoría del texto,
no al origen de la aplicación; también incluye textos históricos sin marca de IA.
Las marcas manuales explícitas prevalecen sobre modelos antiguos. «Todas» incluye
aplicaciones activas sin texto. Las acciones procesan lotes de 100 por cursor UUID,
saltan tareas activas y generan ambas versiones mediante IA.

El contrato `POST /api/v1/admin/scraper/descriptions/regenerate?scope=ai|manual|all`
acepta `after=<UUID>` y devuelve HTTP 202 con `matched`, `enqueued`, `alreadyActive`
y `nextAfter`. Requiere sesión ADMIN y CSRF; cada lote se audita.

Los textos anteriores y su procedencia se conservan ante fallos. Las nuevas
regeneraciones comprueban la huella de ambos textos antes de generar y de guardar,
para no sobrescribir ediciones posteriores al encolado. Las ediciones de cualquiera
de los idiomas se registran como `admin_manual`; guardar textos intactos conserva
su origen. Los intentos fallidos se detallan en logs, sin sustituir la procedencia
correspondiente al texto guardado. No se reconstruyen ediciones históricas sin rastro.
