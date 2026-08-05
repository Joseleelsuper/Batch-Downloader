# Batch-Downloader

⠀⠀⠀
<div align="center">
    <img src="./assets/BatchDownloaderI.png" alt="Batch Downloader" width="400px"/>
</div>
⠀⠀⠀

Permite descargar varios ejecutables de programas conocidos al mismo tiempo. Ideal para poner un nuevo PC en marcha.

## Docker

El despliegue de desarrollo usa `docker-compose.yml`; el despliegue con
imágenes publicadas usa `docker-compose.ghcr.yml`. Ambos leen el mismo `.env`.

```bash
cp .env.example .env
docker compose --env-file .env up --build -d
docker compose --env-file .env ps
```

La interfaz queda disponible a través de Nginx en `http://localhost:3000`.
MySQL conserva el catálogo autoritativo; PostgreSQL/pgvector contiene una
proyección semántica reconstruible.

Cada API y servicio dispone además de un `.env.example` local con sus ajustes
no sensibles. El `.env` de la raíz continúa siendo la fuente global de Docker
Compose y el único lugar para contraseñas, tokens, claves de firma y API keys.
Los `.env` locales se generan sin copiar credenciales:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/sync-service-env-files.ps1
powershell -ExecutionPolicy Bypass -File scripts/sync-service-env-files.ps1 -Check
```

Los procesos que comparten una imagen tienen variantes como `.env.scheduler`,
`.env.indexer`, `.env.model-worker` y `.env.trainer`. Docker Compose sigue
inyectando la configuración global; estos archivos locales sirven para ejecutar
o inspeccionar cada componente de forma aislada cuando se cargan explícitamente
junto con sus credenciales globales.

Para probar las imágenes publicadas en GitHub Container Registry:

```bash
docker compose --env-file .env -f docker-compose.ghcr.yml up -d
```

El Compose GHCR usa imágenes como
`ghcr.io/joseleelsuper/batch-downloader-webapp:main`. Para arrancar sin
`docker login ghcr.io`, los paquetes de GHCR deben estar marcados como públicos.

## Descargas por lotes

Core crea y conserva cada trabajo, y publica por SSE el resultado de cada
aplicación conforme termina. El progreso de items llega hasta el 90 %; el estado
`PACKAGING` representa la generación real del ZIP y el 100 % solo se publica
cuando el archivo ya está disponible.

La interfaz mantiene los trabajos en un overlay global recuperable durante la
sesión, incluso después de navegar o recargar. Al alcanzar `READY`, `PARTIAL` o
`MANUAL_ONLY` solicita una descarga automática una sola vez y conserva siempre
el enlace manual. Los fallos muestran la aplicación afectada y, cuando existe
una página oficial HTTPS segura, el ZIP incluye
`Descargas manuales/<aplicación>.url`.

RabbitMQ transporta exclusivamente identificadores. El worker obtiene la URL
temporal del instalador desde el scraper y solo si hay fallos consulta una vez
el endpoint autenticado de Core para recuperar nombre y página oficial. Nunca
se incorporan URLs resueltas de instaladores a eventos, manifests o logs.

El orden compatible de despliegue es Core —incluida su migración Flyway—,
worker y, por último, frontend.

## Búsqueda literal y semántica

El catálogo admite `searchMode=lexical|semantic`. Si el parámetro no se envía, la
API mantiene `lexical` por compatibilidad. En la interfaz, la precedencia es:
modo de la URL, última elección de `localStorage` y, en la primera visita,
`semantic`.

El modo semántico usa la enumeración producida por el modelo de embeddings como
conjunto de resultados. El orden elegido en el catálogo —más descargadas,
actualizadas o nombre— se aplica en MySQL y la relevancia queda como desempate.
Los filtros, estados, facetas, totales y paginación también se aplican en MySQL.
Si el índice no tiene
cobertura completa, supera 20.000 candidatos, expira o devuelve un error, toda la
petición pasa a literal y la interfaz muestra un aviso sin cambiar la preferencia
guardada.

El filtro inicial de la interfaz es `available`. La URL tiene prioridad sobre la
última selección de estado guardada en `localStorage`.

## Banco de resolución manual

`/admin/apps` abre por defecto la vista administrativa **Por resolver**, que
combina `review` y `missing` sin añadir un estado público nuevo. La mesa
maestro/detalle permite buscar y paginar aplicaciones, recuperar un análisis
abierto tras recargar y continuar usando la creación o edición ordinaria.

El alta de una aplicación nueva parte de su **web oficial** y admite, de forma
opcional, una URI directa para Windows, macOS y Linux. Los huecos se buscan
automáticamente. El scraper mantiene un trabajo recuperable en
`website_app_discovery`, extrae
nombre, editor, versión, icono y descripciones desde evidencia allowlisted,
genera la descripción larga en español y recorre enlaces de descarga para
buscar instaladores. Solo conserva candidatos cuya red, redirects, formato y
firma quedan validados. La previsualización resultante es totalmente editable y
no devuelve las URL de los ejecutables. Las URI aportadas se validan contra
SSRF, se cifran y se vinculan al sistema operativo indicado. Si una web bloquea
con 401/403 una variante con parámetros no sensibles, el scraper puede
reintentar exactamente la misma ruta sin la query manteniendo HTTPS, DNS
público y redirects seguros.

Al confirmar el alta se revalidan los instaladores y se crean aplicación,
fuentes y candidatos en una transacción. Si ya no queda ningún instalador
válido, la aplicación se conserva como `missing`; nunca se publica una descarga
dudosa para forzar `available`.

Para resolver una aplicación, el administrador proporciona una página HTTPS de
origen y al menos una URI HTTPS directa de instalador en los huecos de Windows,
macOS o Linux; los sistemas no disponibles se dejan vacíos. Core exige sesión
`ADMIN`, CSRF y audita la operación. El scraper cifra por separado todas las URI
y encola solamente el identificador de inspección en
`manual_installer_enrichment`. El trabajo valida cada binario contra el sistema
indicado —además de DNS, redirects, tamaño, formato y firma—, obtiene metadatos
allowlisted de JSON-LD/OpenGraph/Twitter y genera una descripción larga en
español cuando hay un proveedor de IA configurado.

La previsualización es editable y muestra la procedencia de cada sugerencia. No
se modifica el catálogo hasta pulsar **Guardar y publicar**. En ese momento se
revalidan todos los binarios, se comprueba la versión optimista de la aplicación
y una transacción crea o reutiliza sus fuentes, cifra los candidatos
`direct`/`valid` y deja que las proyecciones MySQL cambien `review|missing` a
`available`. Un archivo neutral hereda únicamente el hueco de plataforma elegido
explícitamente; nunca se inventa. Las URL finales de los ejecutables no aparecen
en respuestas, auditorías, logs ni cargas de cola.

Los pesos se descargan desde Hugging Face, pero las descripciones, metadatos y
consultas permanecen dentro del despliegue local:

`SEMANTIC_MINIMUM_SIMILARITY=0.82` es el umbral inicial medido para E5-base; se
mantiene configurable porque debe recalibrarse si se promociona otra familia de
embeddings.

```bash
# Ejecutar un barrido e indexar únicamente contentHash nuevos
docker compose --env-file .env run --rm semantic-indexer \
  python -m app.indexer

# Entrenar y comparar MiniLM, E5-base y BGE-M3 (perfil explícito)
docker compose --env-file .env --profile training run --rm semantic-trainer

# Verificar descarga, LoRA, evaluación e informes con un lote acotado
docker compose --env-file .env --profile training run --rm semantic-trainer \
  python -m app.trainer --smoke
```

El entrenador crea un snapshot inmutable por aplicación, divide
train/validation/test, mina negativos difíciles, ajusta con LoRA +
`MultipleNegativesRankingLoss` y genera informes JSON, CSV y Markdown en el
volumen `semantic_reports`. La selección usa 70 % nDCG@10, 20 % latencia inversa
y 10 % RAM/VRAM/índice, sin empeorar MRR@1 literal. Ningún benchmark, descarga o
indexación activa automáticamente su ganador.
Cada versión ajustada conserva el adaptador PEFT y una copia fusionada para
inferencia; el artefacto solo se registra después de recargarlo, comprobar su
dimensión y escribir el marcador de finalización.
El modo `--smoke` usa un paso y un subconjunto determinista y nunca selecciona
ni activa un modelo; sirve únicamente para verificar el flujo reproducible.

## Administración de modelos semánticos

Un administrador puede abrir `/admin/semantic/models`,
`/admin/semantic/benchmarks` y `/admin/semantic/hugging-face`. El navegador solo
habla con Core API; Core aplica sesión `ADMIN`, CSRF y auditoría y usa el token
interno únicamente al comunicarse con `semantic-service`.

`semantic-model-worker` se inicia siempre con Compose y ejecuta una operación
pesada cada vez. Las operaciones se guardan en PostgreSQL con lease, reintentos,
progreso e idempotencia, por lo que continúan después de recargar el navegador o
reiniciar el worker. El flujo de producción es deliberadamente explícito:

1. Descargar una revisión pública e inmutable, exclusivamente con
   `safetensors`, y validarla offline con `trust_remote_code=False`.
2. Compararla con el modelo activo mediante un benchmark completo y vigente.
3. Preparar embeddings e índice para todos los `contentHash` actuales.
4. Calentar el candidato sin cambiar producción.
5. Activar o hacer rollback atómicamente desde la interfaz.

El descubrimiento usa
[`HfApi`](https://huggingface.co/docs/huggingface_hub/en/package_reference/hf_api)
y fija cada descarga a la revisión SHA resuelta antes de llamar a
[`snapshot_download`](https://huggingface.co/docs/huggingface_hub/main/en/package_reference/file_download).
La ficha muestra licencia y estado de los
[escaneos de seguridad del Hub](https://huggingface.co/docs/hub/security).

Los benchmarks `smoke`, heredados o con un catálogo/configuración diferente se
muestran como diagnóstico, pero nunca habilitan preparación o recomendación.
Cambiar el catálogo marca las preparaciones inactivas como `stale`. E5 conserva
su umbral `0.82`; cada modelo nuevo guarda sus propios `queryPrefix`,
`passagePrefix` y `minimumSimilarity`.

Las cuotas se controlan con `SEMANTIC_MODEL_MAX_BYTES` y
`SEMANTIC_MODEL_MIN_FREE_BYTES`; el lease con
`SEMANTIC_OPERATION_LEASE_SECONDS`. No se admiten repositorios privados o
gated, tokens de Hugging Face, pesos pickle, código remoto, LoRA ni entrenamiento
desde esta interfaz.

## Scraper CPython 3.14t

La imagen de producción compila CPython 3.14.6 con `--disable-gil`. El arranque
falla si `Py_GIL_DISABLED != 1` o si el GIL queda activo después de importar las
extensiones nativas. El event loop conserva clientes HTTP, sesiones SQLAlchemy y
Playwright; un `ThreadPoolExecutor` acotado recibe únicamente parseo,
normalización, scoring y deduplicación puros. SQLAlchemy se instala desde su
distribución fuente con `DISABLE_SQLALCHEMY_CEXT=1`: así conserva su
implementación Python y ninguna extensión C suya puede reactivar el GIL.

El perfil `benchmark` compara la imagen 3.14 estándar y la 3.14t con el mismo
driver `aiomysql`, HTML versionado, servidor HTTP controlado, 1/2/4/8 hilos y
cinco repeticiones:

```bash
docker compose --env-file .env --profile benchmark up --build \
  --abort-on-container-failure \
  --exit-code-from scraper-python314-benchmark-report \
  scraper-python314-benchmark-report
```

Produce `python314-standard.json`, `python314t.json` y la comparación
JSON/CSV/Markdown en `semantic_reports`.

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

> Volver al [índice](#índice)
