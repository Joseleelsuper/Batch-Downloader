# Download Worker

Consume comandos de RabbitMQ, revalida fuentes en el scraper, descarga los
instaladores en temporales, genera el ZIP en MinIO y publica progreso a Core.
Las URLs de instaladores no aparecen en eventos, manifiestos ni logs.

## Capacidad en Oracle

El perfil está preparado para **2 OCPU y 12 GB de RAM**. Core administra un
presupuesto global de **10 GiB (10.737.418.240 bytes)** en MySQL. Cuenta las
reservas del pico físico: temporales, ZIP y margen de multipart/metadatos. No
hay límite de cantidad de trabajos concurrentes ni ejecución exclusiva de
trabajos grandes. La cola es FIFO estricta: ningún trabajo adelanta a una
cabecera que todavía no cabe.

La estimación suma los tamaños conocidos y sustituye los ausentes por la
mediana del trabajo, o del catálogo si todos faltan, tras intentar revalidar
sus metadatos. El worker comprueba la reserva antes de escribir y solicita
ampliación si el tamaño real crece. Si no cabe, elimina el intento parcial y
lo reencola conservando su posición; si excede el presupuesto total, falla.

Los hilos virtuales permiten esperar por red sin ocupar un hilo de plataforma
por trabajo. RabbitMQ conserva el ACK pendiente hasta terminar la operación
duradera; su consumidor asíncrono no limita la concurrencia de trabajos.
La inbox H2 persistente conserva el `jobId` y el resultado pendiente antes de
confirmar RabbitMQ: permite recuperar multipart y reenviar el mismo evento tras
un reinicio. Su volumen es necesario para la recuperación. El identificador del
comando protege contra eventos atrasados de un intento anterior.

| Recurso | Ajuste |
| --- | --- |
| Empaquetados simultáneos | `DOWNLOAD_WORKER_PACKAGING_CONCURRENCY=2` |
| Compresión | `DOWNLOAD_WORKER_ZIP_LEVEL=0` |
| HTTP por trabajo/origen | Dos conexiones en cada caso |
| Espacio libre del host | `DOWNLOAD_WORKER_MIN_FREE_SPACE=8GB`, separado del presupuesto |
| Bucket MinIO | `MINIO_ZIP_QUOTA=10GB`, defensa adicional a la reserva física de Core |

## Entrega y limpieza

Core sirve el ZIP mediante `GET /api/v1/download-jobs/{id}/file` con `200/206`,
`Range` y buffers acotados; Nginx no crea una copia temporal. `HEAD` devuelve
metadatos sin abrir una transferencia. Tras empaquetar y borrar temporales,
se reduce la reserva al espacio que queda ocupado por el artefacto.
El evento `download.job.ready` incluye `storageBytes` (ZIP y manifiesto) para
aplicar disponibilidad y reserva atómicamente. El campo es opcional en el
esquema para conservar compatibilidad con eventos históricos; los nuevos
emisores siempre lo publican después de terminar subidas y borrar temporales.

En Edge/Chrome compatibles, la web elige destino durante el clic inicial,
escribe directamente al archivo y confirma el guardado después de cerrar la
escritura y verificar el número de bytes. En los demás navegadores se conserva
la descarga normal, sin afirmar que el ZIP esté guardado en disco.

- Guardado confirmado: solicitar limpieza inmediata.
- Un minuto sin conexiones: limpiar el trabajo abandonado.
- Transferencia conectada sin avanzar cinco minutos: interrumpir y limpiar.
- Espera FIFO conectada: conservar el trabajo mediante latidos cada 15 segundos.

La limpieza espera a que terminen escrituras y transferencias, elimina
temporales, objetos y multipart y después libera la reserva. Si falla un
borrado, conserva la reserva y lo reintenta. Queda un recibo mínimo de
propietario durante una hora para reintentos idempotentes y límites de frecuencia,
sin archivos ni reserva. Los datos operativos y el ZIP desaparecen. Core reconcilia el inventario al iniciar
antes de admitir nuevos trabajos.
En la primera promoción hay que drenar los trabajos antiguos y limpiar sus
archivos por `jobId` antes de activar el nuevo esquema de recuperación.
El lifecycle de MinIO a 24 horas está deshabilitado: solo el coordinador limpia
las descargas, para no borrar transferencias largas que siguen avanzando.

Los endpoints internos, protegidos con el token de servicio, son:

| Servicio y ruta | Uso |
| --- | --- |
| Core `POST /internal/v1/download-jobs/{id}/storage` | Reclamar, ampliar, reducir o reencolar la reserva del intento. |
| Worker `GET /internal/v1/storage/inventory` | Inventario de temporales, objetos, multipart y bytes disponibles. |
| Worker `DELETE /internal/v1/jobs/{id}/files` | Cancelar escritores y confirmar ausencia de todos los archivos antes del `204`. |

## Verificación

El transporte de instaladores conserva HTTPS, validación DNS/SSRF, hashes y
dos reintentos ante timeout, `408`, `429` o `5xx`. Cada intento fallido elimina
su temporal; la reanudación `Range` corresponde a la entrega del ZIP al usuario.

```bash
mvn -B -pl services/download-worker test
```

Observar métricas de trabajos, descargas, empaquetados, disco y errores, junto
con la suma de `download_job_storage.reserved_bytes` en MySQL. La prueba de
carga con fuentes controladas está en [`tst/load/README.md`](../../tst/load/README.md).
