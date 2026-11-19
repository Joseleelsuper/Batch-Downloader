# Diagnóstico de trabajos y capacidad

Core y el worker exponen métricas Prometheus en `/actuator/prometheus` dentro de
la red de servicios. Nginx publica `/api/`, pero no reenvía `/actuator/`.
Configurar la recogida en la red interna; no es necesario abrir estos endpoints
en el dominio público. Los puertos efectivos se leen de los `.env.example` de
ambos servicios. El despliegue de referencia no incluye un servidor Prometheus
ni un panel Grafana: las consultas siguientes permiten configurar uno.

## Señales disponibles

| Métrica | Significado y límites |
| --- | --- |
| `download_jobs{state}` | Instantánea de trabajos conservados en MySQL, por los diez estados del dominio. Incluye `READY`, `PARTIAL`, `MANUAL_ONLY`, `FAILED`, `CANCELLED` y `EXPIRED`; no es un contador acumulado de resultados históricos. |
| `download_storage_reserved_bytes` / `download_storage_limit_bytes` | Reserva global de MySQL y límite de 10 GiB. La reserva incluye el pico previsto, no equivale al tamaño actual del ZIP. |
| `download_storage_queue_depth` | Trabajos en `ESTIMATING` o `QUEUED` en la admisión FIFO de Core. |
| `download_storage_oldest_queued_seconds` | Edad desde la creación del trabajo más antiguo que espera admisión. |
| `download_metrics_last_success_timestamp_seconds` | Hora UTC de la última instantánea completa de Core; cero hasta la primera muestra. |
| `download_metrics_refresh_failures_total` | Fallos al leer la instantánea. Core conserva la muestra anterior y su fecha. |
| `download_worker_queue_depth` / `download_worker_capacity_wait_queue_depth` | Mensajes pendientes en RabbitMQ, incluida la espera por capacidad. No deben sumarse a la cola FIFO de Core: describen etapas diferentes del mismo flujo. |
| `download_worker_queue_consumers` | Consumidores de la cola de entrada. |
| `download_worker_active_jobs` / `download_worker_active_downloads` / `download_worker_active_packagings` | Ocupación local de cada proceso worker. |
| `download_worker_temporary_bytes` | Bytes de instaladores materializados y aún contabilizados por ese proceso. Se reinicia con el worker; el inventario físico y la conciliación de Core permiten detectar restos de procesos anteriores. |
| `download_worker_phase_seconds_{count,sum,max}{phase,outcome}` | Resolución y transferencia por instalador; empaquetado por trabajo, incluida la escritura del ZIP, subida, manifiesto y confirmación de resultado. Resultados: `success`, `rejected`, `cancelled`, `failure`. |
| `download_worker_queue_wait_seconds_*` / `download_worker_packaging_wait_seconds_*` | Espera del evento y de una plaza de empaquetado, respectivamente. |
| `download_worker_capacity_deferred_total{reason}` | Aplazamientos por `temporary_storage_busy`, `storage_budget_wait` u `other`. |
| `download_worker_remote_download_seconds_*{outcome}` | Temporizador previo del transporte; conserva éxito, rechazo y fallo. No se suma al de fase, porque miden la misma transferencia. |

Core actualiza su instantánea cada 15 segundos mediante dos consultas agregadas;
un scrape solo lee memoria. Las dos consultas no forman una transacción de
negocio: una transición concurrente puede producir pequeñas diferencias entre
estados y reservas. RabbitMQ se consulta cada diez segundos. No se deduce una
garantía de disponibilidad de una muestra aislada ni de un contador cero.

Las etiquetas nuevas usan exclusivamente enumeraciones del código. No incluyen
job IDs, cuentas, cookies, correos, dominios, URLs ni mensajes de excepciones.
Los estados desconocidos de MySQL no crean series. Resoluciones manuales sin
fuente no llaman al resolutor ni generan muestras de resolución o transferencia.
Los temporizadores aparecen después de su primera operación; no se habilitan
histogramas ni percentiles que el código no publique.

## Consultas mínimas

Estos ejemplos asumen que los targets del entorno se recogen con los trabajos
Prometheus `batch-core` y `batch-worker`. Añadir un filtro de entorno si el mismo
servidor recoge varios despliegues. Core y RabbitMQ publican información global
repetida por réplica: se usa `max`. La actividad local de workers se suma.
Con el [escalado en un servidor](deployment/escalado-local.md), el cupo de
empaquetado se comparte por volumen: la suma de empaquetados activos debe
respetar las dos plazas de la configuración de referencia.

```promql
# Panel 1: trabajos conservados por estado
max by (state) (download_jobs{job="batch-core"})

# Panel 2: proporción del presupuesto reservado
max(download_storage_reserved_bytes{job="batch-core"})
  / max(download_storage_limit_bytes{job="batch-core"})

# Panel 3: colas y antigüedad de la admisión
max(download_storage_queue_depth{job="batch-core"})
max(download_storage_oldest_queued_seconds{job="batch-core"})
max(download_worker_queue_depth{job="batch-worker"})

# Panel 4: duración media por fase y resultado en cinco minutos
sum by (phase, outcome) (rate(download_worker_phase_seconds_sum{job="batch-worker"}[5m]))
  / sum by (phase, outcome) (rate(download_worker_phase_seconds_count{job="batch-worker"}[5m]))

# Actividad y temporales de los procesos actuales
sum(download_worker_active_downloads{job="batch-worker"})
sum(download_worker_temporary_bytes{job="batch-worker"})

# Frecuencia de fallos de infraestructura y aplazamientos
sum by (phase) (rate(download_worker_phase_seconds_count{job="batch-worker",outcome="failure"}[5m]))
sum by (reason) (rate(download_worker_capacity_deferred_total{job="batch-worker"}[5m]))

# Falta de una instantánea reciente: comprobar también up del target
time() - max(download_metrics_last_success_timestamp_seconds{job="batch-core"}) > 60
```

Ante reserva alta y cola creciente, contrastar los procesos activos y el espacio
libre con el inventario interno antes de aumentar concurrencias. Ante mensajes
pendientes sin consumidores, comprobar salud del worker y conexión a RabbitMQ.
Ante errores de fase, consultar los códigos estructurados del trabajo: las
métricas no contienen datos para identificar a su propietario ni la URL fallida.
Una muestra de Core antigua exige revisar la conexión a MySQL; no interpretar
sus valores conservados como una lectura nueva.

## Evidencias

`DownloadOperationalMetricsMySqlTest` ejecuta las agregaciones en MySQL real,
comprueba estados y reservas, reinicio de estados ausentes, fallos y etiquetas
acotadas. `DownloadWorkerMetricsTest` conserva resultados y excepciones mientras
emite medidas de éxito, rechazo, cancelación y fallo. `DownloadJobProcessorTest`
comprueba la emisión dentro de los recorridos de ZIP parcial y fallo total;
`RabbitQueueMetricsTest` contrasta profundidad y consumidores.

`npm run test:download-flow` comprueba además los endpoints HTTP de Prometheus
en un Compose aislado. Tras generar un ZIP `MANUAL_ONLY`, exige muestras reales
de resolución rechazada y empaquetado correcto, una instantánea reciente de
Core, su límite de 10 GiB y la serie de trabajos fallidos. No acepta comentarios
`HELP`/`TYPE` como muestras; los servicios y volúmenes de la prueba se eliminan
al terminar.

Estas pruebas acreditan la instrumentación versionada. La recogida continua,
retención de series y entrega de alertas dependen de la configuración operativa
del despliegue y deben verificarse allí por separado.
