# Producción en Oracle con Coolify

La producción vive en `51.170.57.42` y Coolify la administra desde
`https://deploy.batchdownloader.dev`. El único tráfico público permitido es TCP
22/80/443. SSH está disponible desde cualquier IP, exclusivamente por clave,
con contraseña y acceso root desactivados.

## Flujo de una versión

1. Un PR contra `main` compila las siete imágenes en AMD64 y ARM64.
2. Al integrar el PR, `publish-ghcr.yml` publica `sha-<commit>` y `main`, y
   comprueba los dos manifiestos de cada imagen.
3. `deploy-production.yml` fija en Coolify el commit y
   `GHCR_IMAGE_TAG=sha-<commit>`, despliega y ejecuta los smoke tests.
4. Si el despliegue o los smoke tests fallan, se restaura automáticamente el
   último commit y tag sanos.

Coolify no despliega por webhook. GitHub Actions es el único iniciador de un
despliegue y los despliegues se serializan.

## Dominios

- `batchdownloader.dev`: web y API.
- `www.batchdownloader.dev`: redirección al dominio principal.
- `downloads.batchdownloader.dev`: proxy legado; las nuevas descargas usan la ruta autenticada `/api/v1/download-jobs/{id}/file` del dominio principal.
- `deploy.batchdownloader.dev`: panel Coolify.

MySQL, PostgreSQL, RabbitMQ, MinIO y los demás servicios internos no tienen
dominio público. Sus puertos del host se ligan a `127.0.0.1`.

## Variables obligatorias

La aplicación de Coolify debe usar las variables de `.env.example`, secretos
nuevos y estos valores de producción:

```dotenv
APP_PUBLIC_BASE_URL=https://batchdownloader.dev
MINIO_PUBLIC_ENDPOINT=https://downloads.batchdownloader.dev
CORE_API_REQUIRE_HTTPS=true
CORE_API_COOKIE_SECURE=true
MINIO_ZIP_QUOTA=10GB
CORE_API_FLYWAY_TARGET=20
SCRAPER_ALEMBIC_TARGET=20260914_0021
SEMANTIC_MODELS_VOLUME_NAME=batch-downloader_semantic_models
```

`GHCR_IMAGE_TAG` siempre debe ser `sha-` seguido del commit completo. Los
secretos de GitHub requeridos son `COOLIFY_URL`, `COOLIFY_TOKEN` y
`COOLIFY_APP_UUID`.

El enlace mágico conserva el TTL configurado en `CORE_API_MAGIC_LINK_TTL`
(15 minutos por defecto).

## Descargas y cambio de versión

El perfil Oracle usa 2 OCPU, 12 GB de RAM, dos empaquetados simultáneos y ZIP
sin compresión. Core reserva un máximo global de 10 GiB para temporales, ZIP y
multipart; un trabajo que no cabe espera en FIFO sin bloquear peticiones HTTP.
El margen libre del host de 8 GiB se comprueba adicionalmente.

Antes de esta promoción, dejar finalizar los trabajos de la versión anterior:
el cambio sustituye los enlaces firmados por streaming observable y aplica
Flyway V20. Al arrancar, Core reconcilia inventario y reservas antes de despachar.
Verificar después dos descargas independientes, cabecera FIFO que no cabe,
reanudación 200/206 y limpieza tras guardado o desconexión. Un fallo de borrado
debe conservar su reserva; comprobar que no reaparecen timeouts de Hikari.
El lifecycle de MinIO a 24 horas debe permanecer deshabilitado: la limpieza
depende exclusivamente del coordinador y de actividad real, no de la edad del ZIP.
No aumentar el pool para ocultar consultas anidadas: la lectura de detalles
libera la conexión principal antes de enriquecer el resultado.

## Modelo y copias

El modelo se instala offline en el volumen
`batch-downloader_semantic_models/current` y se valida antes de arrancar. No se
permiten descargas remotas del modelo.

`ops/backups/install.sh` programa los dumps y las copias frías. Tras configurar
`/etc/batch-downloader/backup.env`, hay que ejecutar una vez cada tipo de copia
y validar las bases con `batch-downloader-verify-restore`. `minio_data` y
`download_worker_temp` se excluyen porque contienen artefactos regenerables.

## Operación

- Despliegue manual de emergencia: ejecutar `Deploy production` con un SHA que
  ya exista en GHCR.
- Rollback: volver a ejecutar el workflow con el SHA sano anterior.
- Diagnóstico: usar logs y métricas del recurso en Coolify; no publicar paneles
  internos.
- Cambio de esquema: detener la promoción automática, crear una copia
  restaurable y ejecutar la migración como una operación separada.
