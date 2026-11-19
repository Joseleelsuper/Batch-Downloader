# Réplicas en un único servidor Docker

El alcance de [RNF-12 / #42](https://github.com/Joseleelsuper/Batch-Downloader/issues/42),
acordado el 6 de noviembre de 2026, permite replicar Core API y Download Worker
por separado en un servidor. MySQL, RabbitMQ, MinIO, `download_worker_temp` y
la entrada web siguen siendo comunes.

## Estado y exclusión

| Estado o recurso | Coordinación |
| --- | --- |
| Sesiones, cuentas, bundles, trabajos, outbox y reserva FIFO | MySQL; Spring Session JDBC, transacciones y bloqueo de admisión existente. |
| Inbox y resultado sellado del worker | MySQL, tabla `download_inbox`; Flyway del worker usa `db/worker-migration` e historial `download_worker_schema_history`, separado de Core. |
| Escritura y limpieza de un trabajo | Bloqueo del núcleo en `.worker-execution.lock`, dentro del volumen local compartido. |
| Empaquetado | Dos plazas para el conjunto de workers en la configuración de referencia. |
| Entregas ZIP abiertas por Core | UUID de proceso y renovación SQL cada diez segundos; caducidad tras dos minutos sin renovar. |
| Conexiones SSE | Cada API vuelve a leer MySQL durante el heartbeat aunque no reciba el evento RabbitMQ. |
| Cuotas de autenticación | MySQL, huellas HMAC con ámbitos separados y ventanas desde el primer intento. Añadir API no multiplica las cuotas. |

La exclusión del trabajo precede al inbox. Un duplicado ocupado se reencola,
sin confirmarlo mientras otro proceso escribe. Con la exclusión adquirida se
recupera inmediatamente un `PROCESSING` abandonado, conservando su resultado
pendiente. Los eventos completados se omiten. La limpieza devuelve `503` si
todavía hay un escritor; Core conserva la reserva para reintentar.

Los bloqueos se liberan al terminar la JVM, incluso tras `SIGKILL`. Se usa un
único canal por proceso sobre un fichero que no debe borrarse ni recrearse
mientras haya workers. Las regiones derivan del UUID; una colisión conservadora
aplaza el trabajo, nunca autoriza dos escritores. No se presupone esta semántica
sobre NFS o volúmenes repartidos entre servidores.

Las réplicas deben compartir ruta, volumen y valor de
`DOWNLOAD_WORKER_PACKAGING_CONCURRENCY`. Se mantienen dos conexiones por trabajo.
El limitador por origen sigue siendo local a cada worker: añadir workers aumenta
el máximo agregado a un origen. El presupuesto de 10 GiB es global y no se
multiplica por el número de réplicas.

Cada API renueva solo sus transferencias. Arrancar otra no las borra ni reinicia
su contador. La conciliación reconstruye los registros heredados `RECONCILING`;
una instantánea antigua no cancela un ZIP recién publicado en el ledger actual.
Los cierres repetidos de un socket no descuentan la referencia de otro.

Las cuotas de acceso conservan como máximo 20.000 huellas por ámbito entre
todas las API; al agotarse el espacio se rechazan claves nuevas sin expulsar
contadores vigentes. No guardan IP, correo ni nombre en claro. La cadencia de
purga es de un minuto y cada petición también retira huellas caducadas de su
ámbito. No se usa una cuota local como alternativa si MySQL falla. Las réplicas
comparten el secreto global y el HMAC distingue el dominio de autenticación del
de propiedad de descargas.

El arranque concurrente puede crear el administrador inicial en otra API.
Ante un conflicto de unicidad o versión, Core vuelve a leer la cuenta confirmada
y solo continúa si es `ADMIN` y su contraseña coincide con la configuración.
Una cuenta `USER` nunca se promociona y los conflictos ajenos se propagan.

## Arranque y cambios de capacidad

Se requiere Compose 2.24.4 o posterior. El override retira los puertos de
diagnóstico fijos de API y worker; se accede por webapp. Nginx actualiza los
destinos mediante el DNS de Docker, sin fijar las IP iniciales.

```bash
docker compose --env-file .env -f docker-compose.yml -f docker-compose.scale.yml up -d --scale core-api=2 --scale download-worker=2
docker compose --env-file .env -f docker-compose.yml -f docker-compose.scale.yml ps
```

Para variar un componente, conservar explícito el número deseado del otro:

```bash
docker compose --env-file .env -f docker-compose.yml -f docker-compose.scale.yml up -d --no-recreate --scale core-api=2 --scale download-worker=3
```

El override también sirve con `docker-compose.ghcr.yml` cuando sus imágenes
incluyan esta versión. Todas las réplicas deben usar el mismo tag inmutable.
Esta configuración conserva un único servidor y no acredita tolerancia a su
pérdida. Editar los archivos no actualiza un despliegue existente.

## Primera actualización desde H2

Antes del primer despliegue hay que impedir nuevas admisiones, dejar terminar
trabajos y entregas, comprobar que no queden comandos pendientes o sin confirmar
y detener los workers y Core antiguos. La actualización aplica Flyway V27 de
Core, V28 de cuotas de autenticación y V1 del worker; después se inician las
réplicas y se contrasta su readiness.

El volumen `download_worker_data` se conserva para la copia previa, pero la
nueva versión no abre su H2. No se mezclan workers antiguos y nuevos. Si no
puede drenarse el sistema, deben recuperarse sus trabajos antes de migrar;
un inbox vacío no justifica descartar resultados pendientes. La copia de MySQL
incluye ahora el inbox del worker. Notification conserva su H2 y una instancia;
no forma parte de este escalado.

## Verificación

`WorkerReplicaMySqlTest` comparte MySQL y volumen entre dos consumidores.
`FileWorkerExecutionLocksTest` contrasta el cupo y mata una JVM independiente.
`DownloadStorageCoordinatorMySqlTest` cubre dos API, sockets vivos y caducidad
selectiva de referencias. Las pruebas de SSE cubren la lectura durante heartbeat.
`JdbcAuthAttemptBudgetMySqlTest` contrasta veinte intentos concurrentes entre
dos adaptadores, caducidad sin prolongar la ventana y saturación de 20.000 huellas.

```powershell
$env:DOWNLOAD_FLOW_REPLICAS='2'
npm --prefix services/webapp/src/main/resources/frontend run test:download-flow
Remove-Item Env:DOWNLOAD_FLOW_REPLICAS
```

Ese modo crea un Compose aislado con dos API y dos workers. Un resolutor
controlado demora el rechazo para contrastar dos trabajos activos; se mata un
worker y se exige que el superviviente complete ambos por redelivery. Se
comprueban inbox, ZIP manual y Prometheus. No usa claves LLM ni binarios externos
y retira servicios y volúmenes al terminar. La existencia del script no acredita
que su ejecución haya pasado: debe registrarse su resultado.

Las métricas y sus límites están en [observability.md](../observability.md).
Referencias: [FileLock de Java 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileLock.html),
[override de Compose](https://docs.docker.com/reference/compose-file/merge/) y
[upstreams de Nginx](https://nginx.org/en/docs/http/ngx_http_upstream_module.html#server).
