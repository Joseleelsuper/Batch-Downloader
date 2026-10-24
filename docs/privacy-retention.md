# Conservación y eliminación de datos

Política de referencia: 24/10/2026. Se conserva el historial mientras exista la cuenta.
Los plazos siguientes describen el código y la configuración versionada; hay que
desplegarlos y comprobarlos en el servidor antes de darlos por efectivos en producción.

| Datos | Conservación y eliminación |
| --- | --- |
| Cuenta, bundles e historial | Mientras exista la cuenta. La baja deshabilita el acceso, invalida sesiones y retira bundles, historial y tokens. El borrado de la cuenta termina al limpiar sus trabajos y ZIP. |
| Enlaces y sesiones | Se retiran los tokens consumidos o caducados y las sesiones caducadas. Las solicitudes de acceso pendientes ya tienen su propio limpiador. Cadencia de referencia: un minuto. |
| ZIP y trabajos | El coordinador mantiene los archivos mientras sean necesarios y los limpia tras guardado confirmado, cancelación o inactividad. El mantenimiento retira los trabajos terminales sin almacenamiento asociado, conservando el historial de cuenta. |
| Recibos mínimos | Una hora después de limpiar el trabajo: permiten controlar la cuota horaria y reintentar el acuse. No contienen instaladores. |
| Correo en el outbox | Destinatario y parámetros se retiran tras el acuse del broker. Los eventos publicados se eliminan en la siguiente pasada. Los correos cuyo enlace ya ha caducado se descartan sin enviarse. |
| Deduplicación y auditoría | Objetivo máximo de 24 horas, descontando la cadencia del limpiador. Se retiran confirmaciones y fallos de correo antiguos, conservando las reservas en procesamiento; el worker protege las entradas asociadas a un trabajo activo. Los errores del proveedor se guardan como tipo de excepción, sin su mensaje. |
| RabbitMQ | El correo recibe un TTL igual a la vigencia restante de su enlace. La cola de correo tiene además un máximo de 15 minutos; su DLQ, un minuto. Las demás DLQ caducan en 23 h 58 min. No se limita por edad la cola de trabajos activos. |
| Logs de la web | El acceso registra fecha, método, código, bytes y duración, sin IP, URI, parámetros, cookies, navegador ni referer. Los errores detallados se guardan con permiso 0600 en `/var/log/nginx/private-error.log`; se vacían al arrancar y cada hora, sin enviarlos a stdout. |
| Copias con datos de usuarios | Objetivo máximo de 24 horas, tanto local como remoto. La purga independiente se ejecuta cada cinco minutos y selecciona copias de al menos 23 h 55 min para dejar margen. |

El catálogo público, los pesos del modelo y las estadísticas agregadas sin identificadores
no necesitan borrarse al cerrar una cuenta. El scraper conserva su diagnóstico de catálogo
según su política independiente; no recibe correos ni historial de cuentas.

## Configuración

Mantener los `.env` por servicio en desarrollo. En GHCR/Coolify añadir los valores de
`.env.example`, sin sustituir secretos ni otras personalizaciones:

```dotenv
CORE_API_RETENTION_INTERVAL=1m
CORE_API_MESSAGE_RETENTION=24h
CORE_API_AUDIT_RETENTION=24h
DOWNLOAD_WORKER_RETENTION_INTERVAL=1m
DOWNLOAD_WORKER_MESSAGE_RETENTION=24h
NOTIFICATION_SERVICE_RETENTION_INTERVAL=1m
NOTIFICATION_SERVICE_MESSAGE_RETENTION=24h
```

Los servicios rechazan plazos superiores a 24 horas o sin margen para su cadencia.
La configuración de referencia de los inbox debe aplicarse junto con las políticas de
RabbitMQ: antes de reducir su ventana o aumentar la cadencia, acotar también el plazo
de reentrega para que ningún mensaje pueda reaparecer después de perder su identificador.
Flyway aplica V23, que añade índices para las limpiezas. Las consultas se limitan a
500 filas por categoría y pasada y preservan trabajo pendiente. Si llega a acumularse
más trabajo de mantenimiento del que puede retirarse, el plazo puede superarse:
vigilar los contadores `operational.retention.*`, los de ambos inbox y sus fallos.
Los cambios de configuración necesitan recrear los contenedores correspondientes.

Las políticas de RabbitMQ se aplican una vez por contenedor, después de verificar su
arranque, mediante el healthcheck. Esto conserva la creación del usuario/vhost de Docker
y evita redeclarar colas existentes con argumentos distintos. No reenviar manualmente
mensajes de DLQ fuera de su ventana; se perdería la garantía de deduplicación. Si se
cambian los nombres de las colas, adaptar los patrones de las políticas.

## Copias en OCI

Actualizar el instalador del repositorio con `sudo bash ops/backups/install.sh`.
En `/etc/batch-downloader/backup.env` usar `BATCH_BACKUP_RETENTION_SECONDS=86400`
o un plazo inferior, mayor de cinco minutos. El instalador programa dos copias diarias
por componente y una purga cada cinco minutos. Las copias frías ya existentes siguen
deteniendo brevemente el componente correspondiente; ahora se realizan cada doce horas.

La selección remota usa los IDs y fechas de **todos** los snapshots del repositorio,
comparados con la hora UTC actual. No depende de que exista una copia nueva ni de la
agrupación por rutas fechadas de Restic. `prune --max-unused 0` retira los bloques que
han dejado de estar referenciados. El modelo público conserva sus tres últimas copias,
agrupadas por etiqueta, y queda fuera de la caducidad de datos personales.
La [documentación de Restic](https://restic.readthedocs.io/en/stable/060_forget.html)
explica la agrupación y las políticas de conservación; las políticas de colas siguen
el [TTL de RabbitMQ](https://www.rabbitmq.com/docs/3.13/ttl).

La selección y el borrado fallan con error si Restic no puede listar o interpretar los
snapshots; no se ejecuta un borrado con una lista inválida. La limpieza local solo toca
los formatos de copia de los cinco directorios previstos, sin recursión ni enlaces
simbólicos. La purga se serializa con las copias mediante `flock`: un bloqueo prolongado,
un fallo de red o una tarea caída puede superar el objetivo de edad y requiere aviso.
Tras el borrado se vuelve a listar y comprobar la edad de los snapshots; una copia
todavía fuera de corte produce un error. Cron omite la salida ordinaria de la purga y
escribe por stderr si falla o no consigue el bloqueo. Su correo debe entregarse al
operador y cualquier fallo debe generar una incidencia.

En OCI revisar que versionado, retención inmutable y reglas de objetos antiguos no
conserven versiones ya eliminadas por Restic. Este repositorio no cambia esas opciones
del bucket ni acredita su estado. Las copias de MinIO y los temporales de descarga
siguen excluidos.

## Restauración y comprobación

Ejecutar `batch-downloader-verify-restore` en un entorno aislado. Antes de sustituir una
base de producción, detener el acceso de la aplicación y contrastar las bajas posteriores
a la copia; reaplicarlas antes de abrir el servicio. Una copia anterior puede contener
una cuenta eliminada después de generarla. No hay un registro externo de bajas resistente
a la pérdida completa del VPS: la recuperación de ese escenario sigue necesitando un
procedimiento específico y no se presenta como resuelta por esta política.

Las pruebas focalizadas cubren el corte temporal, el mantenimiento del historial,
trabajos activos, sesiones válidas y borrado en cascada de sesiones caducadas con MySQL;
también caducidad de correo, saneamiento de destinatarios y repositorios Restic locales.
La verificación completa en OCI y la retención del proxy externo de Coolify, sus copias
y los proveedores deben comprobarse por separado. No se ejecutan purgas de producción
al ejecutar las pruebas del repositorio.
