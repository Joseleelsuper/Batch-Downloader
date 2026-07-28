# Batch Downloader Scraper

Servicio interno Python para sincronizar el catalogo de Winstall, resolver instaladores
desde webs oficiales y exponer endpoints MVP de busqueda/descarga.

## Comandos

```bash
pip install -r requirements.txt
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

Configuración: `SCRAPPER_MANUAL_INSPECTION_TTL_HOURS`,
`SCRAPPER_MANUAL_INSPECTION_MAX_ATTEMPTS` y
`SCRAPPER_MANUAL_PAGE_MAX_BYTES`.

## Generacion de descripciones

La clave `SCRAPPER_LLM_GROQ_API_KEY` activa Groq. El modelo primario se configura
con `SCRAPPER_LLM_GROQ_MODEL`; cuando alcanza una cuota o sufre un fallo transitorio,
el descriptor rota, sin duplicados, por estos modelos de texto:

1. `llama-3.3-70b-versatile`
2. `qwen/qwen3-32b`
3. `qwen/qwen3.6-27b`
4. `meta-llama/llama-4-scout-17b-16e-instruct`

La lista puede sobrescribirse mediante `SCRAPPER_LLM_GROQ_FALLBACK_MODELS` como un
array JSON. `openai/gpt-oss-120b` no se incluye por defecto porque el endpoint
Groq ha devuelto `400` con el formato JSON estricto usado por este enriquecedor.
Cada modelo limitado entra en cooldown; las cabeceras `Retry-After` y
`X-RateLimit-Reset-*` prevalecen sobre
`SCRAPPER_LLM_RATE_LIMIT_COOLDOWN_SECONDS`. Los fallos transitorios usan
`SCRAPPER_LLM_TRANSIENT_COOLDOWN_SECONDS`; un `400` de compatibilidad de modelo
enfria solo ese modelo y permite probar el siguiente fallback.

Si todos los modelos Groq aplicables fallan o estan en cooldown, DeepSeek se usa como
ultimo proveedor solo cuando `SCRAPPER_LLM_DEEPSEEK_API_KEY` esta configurada. Los
logs y la descripcion persistida incluyen siempre el proveedor y el modelo que
produjeron el resultado.
