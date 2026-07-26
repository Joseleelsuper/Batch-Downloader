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

Para probar las imágenes publicadas en GitHub Container Registry:

```bash
docker compose --env-file .env -f docker-compose.ghcr.yml up -d
```

El Compose GHCR usa imágenes como
`ghcr.io/joseleelsuper/batch-downloader-webapp:main`. Para arrancar sin
`docker login ghcr.io`, los paquetes de GHCR deben estar marcados como públicos.

## Búsqueda literal y semántica

El catálogo admite `searchMode=lexical|semantic`. Si el parámetro no se envía, la
API mantiene `lexical` por compatibilidad. En la interfaz, la precedencia es:
modo de la URL, última elección de `localStorage` y, en la primera visita,
`semantic`.

El modo semántico ordena exclusivamente la enumeración producida por el modelo
de embeddings. Los filtros, estados, facetas, totales y paginación se aplican
en MySQL. Si el índice no tiene
cobertura completa, supera 20.000 candidatos, expira o devuelve un error, toda la
petición pasa a literal y la interfaz muestra un aviso sin cambiar la preferencia
guardada.

El filtro inicial de la interfaz es `available`. La URL tiene prioridad sobre la
última selección de estado guardada en `localStorage`.

El indexador descarga pesos de Hugging Face, pero las descripciones, metadatos y
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
y 10 % RAM/VRAM/índice, sin empeorar MRR@1 literal. El ganador solo se activa
cuando todos los documentos activos conservan el `contentHash` indexado.
Cada versión ajustada conserva el adaptador PEFT y una copia fusionada para
inferencia; el artefacto solo se registra después de recargarlo, comprobar su
dimensión y escribir el marcador de finalización.
El modo `--smoke` usa un paso y un subconjunto determinista y nunca selecciona
ni activa un modelo; sirve únicamente para verificar el flujo reproducible.

La versión anterior no se borra. Para una promoción o rollback atómico con
cobertura completa:

```bash
docker compose --env-file .env run --rm semantic-trainer \
  python -m app.model_admin activate \
  'multilingual-e5-base@REVISION:zero-shot' --rrf-weight 1.0
```

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
