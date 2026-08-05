# Prueba de capacidad para 4 cores y 1 SSD

La suite `capacity.js` ejecuta 100 VUs durante 15 minutos, una ráfaga de 20
trabajos en 10 segundos, dos ciclos completos de ZIP, dos conexiones SSE de más
de 10 minutos y un soak posterior de 60 minutos.

Requisitos:

- despliegue ya iniciado por el usuario y accesible mediante `BASE_URL`;
- k6 con el módulo oficial `k6/browser` y Chromium;
- dos aplicaciones de prueba, cada una apuntando a una fuente controlada de
  1-2 GB, en `APP_IDS=id-1,id-2`;
- opcionalmente, cien cuentas `USERNAME_PREFIX1..100` con la misma
  `USER_PASSWORD` para incluir login real.

Ejemplo desde PowerShell:

```powershell
$env:BASE_URL = 'http://localhost:3000'
$env:APP_IDS = 'uuid-fuente-1,uuid-fuente-2'
$env:K6_BROWSER_HEADLESS = 'true'
k6 run .\tst\load\capacity.js
```

Durante la ejecución se deben recoger `/actuator/prometheus` de Core, Download
Worker, notificaciones y traducción; `/internal/v1/metrics` de scraper y
semantic; `http://rabbitmq:15692/metrics`; y
`http://minio:9000/minio/v2/metrics/cluster`. CPU, RAM, iowait, disco y swap del
host se recogen con las herramientas del host, sin desplegar contenedores de
monitorización adicionales. La comparación entre
`navigation_baseline` y `navigation_contended` debe mantenerse dentro del 20 %;
los umbrales HTTP, auth y creación de trabajos ya están codificados en la suite.

La descarga de verificación solicita solo el primer MiB con `Range` desde la URL
firmada, de modo que comprueba el `303` de Core y el soporte directo de MinIO sin
duplicar decenas de gigabytes de tráfico de laboratorio.
