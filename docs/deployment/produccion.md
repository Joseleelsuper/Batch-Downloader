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
4. Si el despliegue o los smoke tests fallan, se intenta restaurar automáticamente
   el último commit y tag sanos; esto no revierte las migraciones de base de datos.

Coolify no despliega por webhook. GitHub Actions es el único iniciador de un
despliegue y los despliegues se serializan.

## Parche de Oracle de octubre de 2026

Los servicios Java se construyen y ejecutan con Temurin 25.0.5+8, con binarios
oficiales verificados por SHA-256. Además se aplica la [mitigación de eMudhra](https://ops.java/emudhra)
en compilación, ejecución y CI: se retiran las raíces emSign G1, G2, G3 y AAA Certificate Services,
según la actualización del 24/10/2026 para [CVE-2026-114820](https://www.oracle.com/security-alerts/alert-cve-2026-114820.html).
Java 25.0.5 por sí solo no sustituye esta mitigación.
MySQL queda fijado en 8.4.13 por el [CPU de octubre](https://www.oracle.com/security-alerts/cpuoct2026.html).
Las cuatro aplicaciones Java se ejecutan con UID/GID `10001:10001`. Antes de
iniciar worker y notificaciones, el job `java-data-init` prepara la propiedad
de sus volúmenes persistentes y del temporal del worker, conservando su contenido.
Este job se ejecuta como root, sin red y con el resto del sistema de archivos
en solo lectura; las aplicaciones mantienen su usuario sin privilegios.
Para trasladar el parche a producción hay que publicar y desplegar las nuevas
imágenes y recrear MySQL con copia restaurable previa; editar los Compose no actualiza
un contenedor existente. Si se configura un almacén Java personalizado, debe recibir
también la mitigación y no reutilizar la copia anterior de `cacerts`.

## Dominios

- `batchdownloader.dev`: web y API.
- `www.batchdownloader.dev`: redirección al dominio principal.
- `downloads.batchdownloader.dev`: proxy legado; las nuevas descargas usan la ruta autenticada `/api/v1/download-jobs/{id}/file` del dominio principal.
- `deploy.batchdownloader.dev`: panel Coolify.

MySQL, PostgreSQL, RabbitMQ, MinIO y los demás servicios internos no tienen
dominio público. Sus puertos del host se ligan a `127.0.0.1`.

## Variables obligatorias

El Compose GHCR no incorpora valores de entorno por defecto ni carga `.env` por servicio. Antes de desplegar esta configuración sobre una instalación existente, añade en Coolify los ajustes nuevos de `.env.example`, conservando los secretos y personalizaciones existentes. Los ajustes obligatorios usan `${VARIABLE:?}`: Compose rechaza tanto su ausencia como un valor vacío. Las entradas que admiten vacío conservan `${VARIABLE?}`. El workflow comprueba los ajustes obligatorios del Compose antes de modificar el commit o el tag de producción; las variables vacías creadas automáticamente por Coolify no sustituyen sus valores de configuración.

La aplicación de Coolify debe usar las variables de `.env.example`, secretos
nuevos y estos valores de producción:

```dotenv
APP_PUBLIC_BASE_URL=https://batchdownloader.dev
MINIO_PUBLIC_ENDPOINT=https://downloads.batchdownloader.dev
CORE_API_REQUIRE_HTTPS=true
CORE_API_COOKIE_SECURE=true
MINIO_ZIP_QUOTA=10GB
SEMANTIC_MODELS_VOLUME_NAME=batch-downloader_semantic_models
```

`GHCR_IMAGE_TAG` siempre debe ser `sha-` seguido del commit completo. Los
secretos de GitHub requeridos son `COOLIFY_URL`, `COOLIFY_TOKEN` y
`COOLIFY_APP_UUID`.

Al arrancar, Alembic aplica `head` y Flyway aplica todas las migraciones pendientes
incluidas en la imagen, sin números objetivo en el entorno. Las migraciones conservan
sus comprobaciones de compatibilidad y bloquean el arranque si no se cumplen.
El paso desde Alembic `20261012_0024` incluye `0022` y `0023`: retira objetos
obsoletos y las tablas históricas de snapshots. Antes del primer despliegue,
conserva una copia restaurable de MySQL; el rollback de imágenes no recupera esos datos.

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

La [política de conservación](../privacy-retention.md) conserva el historial mientras
exista la cuenta y fija un objetivo máximo de 24 horas para copias con datos personales.
Actualizar el instalador de copias y configurar las nuevas variables de retención de
`.env.example`: los limpiadores se ejecutan cada minuto y el VPS purga copias cada cinco.
La purga remota comprueba la edad absoluta de los snapshots y retira bloques sin referencias.
Revisar por separado el versionado del bucket OCI y los logs del proxy de Coolify.

## Operación

- Despliegue manual de emergencia: ejecutar `Deploy production` con un SHA que
  ya exista en GHCR.
- Rollback: volver a ejecutar el workflow con el SHA sano anterior.
- Diagnóstico: usar logs y métricas del recurso en Coolify; no publicar paneles
  internos.
- Migraciones: Core y el scraper las aplican al arrancar hasta sus objetivos
  configurados. El servicio semántico aplica todas las pendientes de su imagen,
  con bloqueo y comprobación de checksums, antes de aceptar peticiones; el
  indexador espera a que esté saludable. No requiere un contenedor migrador.
- Cambio de esquema: disponer de una copia restaurable antes del despliegue.
  Una imagen semántica anterior que no incluya las migraciones ya aplicadas
  rechazará el arranque; los cambios incompatibles necesitan una recuperación
  planificada, no solo volver al tag anterior.
