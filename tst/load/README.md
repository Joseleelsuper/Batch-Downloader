# Prueba de capacidad para el VPS de 8 vCore y 24 GB

`capacity.js` entrega el escenario reproducible; no inicia ni reinicia la
aplicación. El despliegue y las fuentes de laboratorio deben estar preparados
por quien ejecute la prueba.

El perfil base combina durante 15 minutos:

- 1.000 VUs con cookies independientes; tras un arranque escalonado realizan
  aproximadamente 100 solicitudes API por segundo;
- 50 cuentas autenticadas que crean 50 jobs y mantienen una conexión SSE por
  job durante 610 segundos;
- opcionalmente, 50 jobs ya `READY` cuyos propietarios solicitan `/file` y
  transfieren el ZIP directamente desde la URL firmada de MinIO.

Los jobs y las transferencias finales son escenarios separados porque los 50
IDs preparados para descargar pueden pertenecer a una ejecución anterior. Así
se pueden repetir las transferencias sin volver a generar 50 ZIP ni superar el
límite global de jobs no terminales.

## Datos necesarios

- Un despliegue ya iniciado y accesible mediante `BASE_URL`.
- k6 con el módulo oficial `k6/browser` y Chromium.
- Al menos dos aplicaciones de laboratorio de 1-2 GB y tamaño declarado, bajo
  control del operador, en `APP_IDS=id-1,id-2`.
- Cincuenta cuentas `USERNAME_PREFIX1..50` con la misma `USER_PASSWORD`. Cada
  cuenta crea un job, por lo que no se depende de los límites anónimos por IP.
- Para probar la entrega final, exactamente 50 UUID en `READY_JOB_IDS`, en el
  mismo orden de propietarios: el primer job pertenece a la cuenta 1, etc.

No se deben usar orígenes de terceros en una prueba de carga. Dos aplicaciones
pueden apuntar a hostnames controlados distintos para observar tanto el límite
global como el límite de dos descargas por origen.

## Ejecución

Carga API, admisión, cola y SSE:

```powershell
$env:BASE_URL = 'https://batch.example.test'
$env:APP_IDS = 'uuid-fuente-1,uuid-fuente-2'
$env:USERNAME_PREFIX = 'load-user-'
$env:USER_PASSWORD = 'contraseña-de-laboratorio'
$env:K6_BROWSER_HEADLESS = 'true'
k6 run .\tst\load\capacity.js
```

Para añadir las 50 transferencias completas, se suministran los jobs listos y
no se define `FINAL_RANGE`:

```powershell
$env:READY_JOB_IDS = (Get-Content .\ready-job-ids.txt) -join ','
k6 run .\tst\load\capacity.js
```

Para comprobar `Range` sin transferir los ZIP completos:

```powershell
$env:FINAL_RANGE = 'bytes=0-16777215'
k6 run .\tst\load\capacity.js
```

Variables opcionales: `WEB_DURATION`, `SSE_DURATION_MS`, `JOB_MAX_DURATION`,
`FINAL_START_TIME`, `FINAL_MAX_DURATION` y `FINAL_TRANSFER_TIMEOUT`. La URL
firmada debe resolver desde el host que ejecuta k6; en producción debe usar el
hostname HTTPS dedicado, no el endpoint interno `minio:9000`.

## Criterios y telemetría

La suite aplica automáticamente:

- errores inesperados inferiores al 1 %;
- p95 inferior a 750 ms para tráfico API, navegación, creación de jobs y firma
  de la redirección cuando se habilitan las transferencias;
- más del 99 % de checks correctos.

Durante la ejecución se deben recoger `/actuator/prometheus` de Core y Download
Worker, las métricas de RabbitMQ y
`/minio/v2/metrics/cluster` de MinIO. En el host se registran CPU, RAM, iowait,
espacio libre y swap. Los criterios que k6 no puede observar directamente son:

- espera p95 del pool Hikari de Core inferior a 100 ms;
- RAM total del despliegue inferior a 20 GB;
- espacio libre del volumen temporal siempre igual o superior a 30 GB;
- nunca más de 8 jobs normales, 16 descargas remotas, 2 descargas por job, 2 por
  hostname, 4 empaquetados y 64 transferencias finales;
- 50 jobs como máximo: 8 reclamados y hasta 42 esperando;
- los bytes de los ZIP se sirven por el hostname MinIO y no por Core.

Para evaluar estos límites se observan `download_worker_active_jobs`,
`download_worker_active_downloads`, `download_worker_host_active_downloads`,
`download_worker_active_packagings`, `download_worker_queue_depth`, las métricas
de reservas de disco/artefactos, `core_download_sse_connections_active` y
`core_download_signed_redirects_total`. Si se produce un `503 storage_busy`, la
respuesta debe incluir `Retry-After: 30` y el porcentaje debe permanecer dentro
del umbral de error establecido.
