# Prueba de capacidad para Oracle (2 OCPU, 12 GB)

`capacity.js` usa fuentes controladas y un despliegue ya iniciado. No inicia
servicios ni modifica su configuración. El perfil base dura 15 minutos y usa
100 sesiones API, 12 trabajos con SSE y latidos de espera, más entregas finales
opcionales por la ruta autorizada de Core. Son parámetros de prueba, no topes
de concurrencia de producción.

## Ejecución

Se necesita k6 con `k6/browser` y Chromium, dos aplicaciones de laboratorio de
unos 100 MiB con tamaños declarados y un token magic link vigente por cada
cuenta de prueba. No usar proveedores ajenos para generar carga.

```powershell
$env:BASE_URL = 'https://batch.example.test'
$env:APP_IDS = 'uuid-fuente-1,uuid-fuente-2'
$env:MAGIC_LINK_TOKENS = '["token-1", "token-2", "...", "token-12"]'
$env:K6_BROWSER_HEADLESS = 'true'
k6 run .\tst\load\capacity.js
```

`SESSION_VUS` y `JOB_VUS` ajustan el número de sesiones y trabajos. También se
pueden ajustar `WEB_DURATION`, `SSE_DURATION_MS` y `JOB_MAX_DURATION`. Tras
preparar el ZIP, este escenario deja de enviar actividad: verifica la limpieza
por abandono sin fingir que el archivo se guardó en un ordenador.

Para comprobar entregas finales, añadir `READY_JOB_IDS` con `JOB_VUS` UUID
recién preparados (menos de un minuto sin actividad), en orden de propietario,
y `FINAL_MAGIC_LINK_TOKENS` con nuevos tokens de esas cuentas. Se prueban
`HEAD` y `GET 200` desde el mismo origen, sin URLs firmadas. Estas transferencias
empiezan inmediatamente y sus cuerpos se descartan: no confirman guardado.

```powershell
$env:READY_JOB_IDS = (Get-Content .\ready-job-ids.txt) -join ','
$env:FINAL_MAGIC_LINK_TOKENS = '["nuevo-token-1", "...", "nuevo-token-12"]'
# Opcional: reanudación parcial en lugar del ZIP completo.
$env:FINAL_RANGE = 'bytes=0-16777215'
k6 run .\tst\load\capacity.js
```

## Criterios

La suite comprueba errores inesperados inferiores al 1 %, más del 99 % de checks
correctos y p95 inferior a 750 ms en API, creación y metadatos. Son objetivos
para contrastar con resultados medidos, no resultados ya alcanzados.

Registrar CPU, RAM, swap, espacio del host y métricas Core/Worker, RabbitMQ y
MinIO. La validación funcional adicional debe confirmar:

- Suma de reservas globales e inventario dentro de 10.737.418.240 bytes.
- Más de ocho trabajos pequeños admitidos cuando caben; dos empaquetados,
  dos conexiones por trabajo y dos por origen.
- Un trabajo grande en cabecera impide que otro menor lo adelante.
- Ampliación o reencolado ante una estimación insuficiente, sin exceder bytes.
- `GET 206`, `If-Range` y rechazo 416 de rangos inválidos.
- Limpieza al minuto sin conexión y a los cinco minutos sin avance; FIFO
  conectado conserva su lugar. La reserva solo desaparece tras borrar archivos.
- Guardado real en Edge/Chrome: archivo cerrado, tamaño exacto, confirmación
  automática y evento SSE `removed` tras la limpieza.
- Ningún timeout del pool Hikari provocado por consultas anidadas del catálogo.

El escenario no constituye por sí solo una prueba del guardado en disco ni de
la recuperación tras reiniciar servicios; esas verificaciones son separadas.
