# Cierre funcional para enero de 2027

El depósito está previsto para el 12–14 de enero de 2027 y la defensa para el 18–22. El objetivo interno de cierre es el 8 de enero. Las fechas prospectivas y el trabajo pendiente se mantienen en [Project 6](https://github.com/users/Joseleelsuper/projects/6/views/5).

## Decisiones del autor del 5 de noviembre de 2026

- La entrega incluye personalización temporal, clonación y solicitudes de software. El 14 de noviembre el autor retiró las propuestas semánticas de bundles del alcance.
- Se permiten estrellas en bundles propios públicos. Se conserva la unicidad por cuenta y bundle y se impiden las valoraciones de bundles privados. La [issue #18](https://github.com/Joseleelsuper/Batch-Downloader/issues/18) registra la sustitución del requisito original.
- Los cambios se realizan directamente en `development`, por indicación expresa del autor. Las referencias a commits sustituyen los enlaces a PR cuando no existe un PR real.
- Los avisos de finalización de descargas por correo (#29) quedan excluidos de enero por decisión expresa del autor. Su issue se conserva como no planificada; el correo de acceso mediante magic link continúa dentro del alcance.

El 6 de noviembre el autor acotó el escalado (#42) a varias réplicas de Core y
del worker en un mismo servidor Docker. Su verificación debe incluir estado
compartido, límites globales y recuperación tras la caída de una réplica.

Ese mismo día confirmó mantener la retirada de las listas de dominios por
fuente de RF-22. La migración `20260709_0005_remove_domain_allowlist` ya eliminó
`source_allowed_domains` en julio. Su issue conserva el requisito original como
histórico y se cierra por cambio de alcance, sin presentar aquella lista como
implementada. La política vigente valida HTTPS, DNS público, redirecciones y
el contenido del instalador; RNF-01 y las pruebas de resolutores recogen esa
seguridad. No se recupera una configuración de listas por fuente para enero.

El autor también concretó RNF-07 (#37) en la auditoría administrativa y la
protección de secretos. La aplicación autentica a sus usuarios mediante sesiones
y no ofrece claves API de cliente. Este requisito no añade un registro de
llamadas a proveedores de IA ni nuevas llamadas a sus servicios.
La [auditoría administrativa](../administrative-audit.md) distingue eventos de
negocio, errores HTTP que llegan a MVC y fallos de escritura del propio log;
no presupone cobertura de las denegaciones previas a MVC.

## Selecciones editables

Desde el detalle de un bundle se puede abrir **Personalizar selección**. La selección se conserva únicamente en el estado de la página. Añadir, retirar o reordenar aplicaciones no cambia el bundle original. La descarga envía los `appIds` al mismo flujo de trabajos que una selección del catálogo, con sus validaciones y límites.

Una cuenta `USER` puede clonar un bundle público u oficial: revisa la selección, elige el nombre y confirma **Guardar como bundle privado**. La creación utiliza el endpoint personal existente, genera una nueva identidad y asigna la propiedad a la cuenta autenticada. El original conserva sus items. El detalle de origen solo incluye aplicaciones activas; una aplicación retirada entre la revisión y el guardado vuelve a validarse en Core y puede impedir el guardado. La copia puede contener aplicaciones activas sin instalador, pero su descarga sigue la política vigente de disponibilidad y alternativas manuales.

La personalización temporal no se indexa como página pública. La búsqueda semántica del catálogo se mantiene disponible.

## Solicitudes de software

Una cuenta `USER` puede proponer nombre y página oficial HTTPS y consultar exclusivamente su propio historial. Se valida la sintaxis sin DNS ni HTTP, se rechazan credenciales, puertos alternativos, IP literales y dominios locales evidentes. La validación no acredita que el dominio sea oficial ni que su resolución sea pública: la propuesta queda pendiente de revisión y nunca provoca una descarga automática.

Se normalizan nombre, dominio, puerto HTTPS predeterminado y fragmentos para detectar duplicados de la misma cuenta. También se comprueban coincidencias evidentes de nombre o URL en aplicaciones activas del catálogo. No se revela si otra cuenta ha propuesto la misma aplicación. El máximo es de cincuenta solicitudes por cuenta; las restricciones únicas y el bloqueo de la cuenta impiden altas duplicadas concurrentes.

La administración acepta o rechaza con un motivo y control de versión. Aceptar una solicitud no publica una aplicación ni modifica el catálogo: la incorporación utiliza la revisión de fuentes existente. La auditoría guarda actor, identificador, estado y resultado, sin copiar URL, nombre ni motivo. Flyway V26 mantiene estas propuestas en Core y la baja de cuenta las elimina de inmediato; la política de privacidad informa del tratamiento.

## Publicación y mantenimiento de bundles

El editor personal muestra un enlace compartible basado en el UUID después de
que el servidor confirme que el bundle es público. El enlace no depende de su
slug editable. Si falla el cambio a privado, sigue mostrando el estado público
persistido; solo retira el enlace tras confirmarse la modificación. El selector
de publicación informa de la exposición pública del nombre y contenido.

El detalle administrativo cuenta las asociaciones a aplicaciones inactivas sin
revelar sus nombres o identificadores en la vista pública. El editor oficial
advierte de que esas aplicaciones no están en la selección visible y exige
aceptar su retirada antes de guardar. Cargar otro bundle o guardar reinicia esa
aceptación. La eliminación del bundle requiere confirmación; sus aplicaciones
permanecen en el catálogo. Si falla la petición, el editor conserva sus datos.

## Evidencia de fuentes e inspección

El detalle de una aplicación distingue web oficial y dominio de descarga. La
versión, el tamaño conocido y la fecha de comprobación pertenecen a la fuente
exacta seleccionada. No se sustituye una fecha desconocida por `updatedAt`, ni
un tamaño desconocido por el de otra variante. El tamaño se expresa en MiB.

Una inspección manual completada muestra los dominios y estados de las
redirecciones HTTP observadas durante la consulta de metadatos. La lista vacía
indica que se observó una respuesta directa; `null` indica que la traza no está
disponible, como en inspecciones históricas. No se muestran rutas, query ni
cabeceras `Location`. La traza usa la navegación del validador existente, sus
comprobaciones de destino y su límite de saltos; no provoca peticiones nuevas.
Un fallo de inspección conserva su código saneado y no se presenta como una
traza completa. Las líneas de la traza pueden partirse en móvil.

## Evidencias iniciales


| Comportamiento | Evidencia |
| --- | --- |
| Descarga anónima de una selección modificada, sin persistencia | `BundleDraftPage.test.tsx` |
| Copia privada confirmada, independiente del original | `BundleDraftPage.test.tsx`, `UserBundleRepositoryTest` |
| Arquitectura y mínimo de etiquetas combinados en MySQL | `CatalogTagMinimumMySqlTest` |
| Ambos sentidos, empates, paginación y alfabeto de bundles | `BundleCatalogMySqlTest` |
| Estrellas propias, unicidad concurrente y privacidad | `BundleCatalogMySqlTest`, `BundleStarSecurityTest` |
| Errores legibles que conservan códigos técnicos | `downloads/failures.test.ts`, `GlobalDownloadJobOverlay.test.tsx` |
| Solicitudes válidas, URL maliciosas, propiedad, duplicados concurrentes, revisión y baja | `SoftwareRequestInputTest`, `SoftwareRequestMySqlTest`, `SoftwareRequestSecurityTest`, `AccountDeletionServiceTest` |
| Formulario, historial, conflicto de versión y teclado ES en ambos tamaños | `SoftwareRequestsPage.test.tsx`, `scripts/e2e/keyboard-accessibility.mjs` |
| Publicar, retirar y republicar; propiedad y enlace confirmado por el servidor | `BundleCatalogMySqlTest`, `AccountPages.test.tsx` |
| Aviso de inactivas, aceptación y retirada confirmada; permisos y auditoría | `BundleCatalogMySqlTest`, `BundleStarSecurityTest`, `AdminBundlesPage.test.tsx` |
| Metadatos por fuente, tamaños y fechas desconocidos, escritorio y móvil | `CatalogProjectionRepositoryMySqlTest`, `AppDetailsPanel.test.tsx`, `scripts/e2e/keyboard-accessibility.mjs` |
| Redirecciones acotadas y saneadas, contrato FastAPI y puente Java | `test_validator.py`, `test_manual_installer.py`, `test_internal_routes.py`, `ScraperInternalClientTest`, `AdminAppsSupport.test.tsx` |
| Resultado de auditoría, errores sin texto privado, CSRF y consulta ADMIN | `AdminAuditServiceTest`, `AdminFailureAuditInterceptorTest`, `AdminAppSecurityTest` |

El primer corte funcional pasó 356 pruebas de frontend con 88,37 % de cobertura
de líneas. El corte de mantenimiento del 6 de noviembre pasó 362 pruebas con
87,75 % de líneas y un solo proceso; la incorporación posterior del enlace
público pasó las 17 pruebas de cuentas, typecheck, lint y build. Los recuentos de
distintas selecciones no se suman. El contrato principal y el perfil público
de IA se comprueban con las quince pruebas de `scripts/quality/tests`.

El scraper pasó 463 pruebas, ninguna omitida, con 73,28 % de cobertura conjunta
en Windows y 73,25 % al repetirlas en Docker con sus dependencias fijadas. El
GIL siguió deshabilitado al terminar esa ejecución de CPython 3.14.10t. El
servicio semántico pasó 54 pruebas en Docker, sin omisiones, con 68,91 % de
cobertura conjunta, Ruff y MyPy. Los resultados y sus límites se conservan en
[`evidencias/validacion-2026-11-06.json`](evidencias/validacion-2026-11-06.json).


Las solicitudes de software han superado otras 37 pruebas Java focalizadas, ninguna omitida, incluyendo MySQL real, migración completa, CSRF y separación de roles. El recorrido de teclado, con API simulada, cubre el alta como usuario, la revisión como administrador y la consulta posterior del motivo como usuario, en ambos tamaños de pantalla.

La observabilidad (#43) ha superado 30 pruebas Java focalizadas sin omisiones,
incluyendo MySQL y los recorridos de éxito y fallo del worker. El Compose aislado
ha contrastado por HTTP las muestras de Prometheus de Core y worker después de
crear el ZIP manual. Las métricas y consultas de diagnóstico se describen en
[`docs/observability.md`](../observability.md); no se presupone un servicio
Prometheus o Grafana desplegado.

El escalado en un servidor (#42) se ha contrastado en un Compose aislado con
dos Core y dos workers: dos trabajos concurrentes, caída forzada de un worker
ocupado y finalización por la réplica superviviente. Las pruebas Java incluyen
la exclusión entre procesos, liberación de locks tras `SIGKILL`, inbox MySQL,
leases de transferencia y cuotas de autenticación compartidas. La prueba no
utiliza los proveedores LLM ni acredita una actualización de producción. El
procedimiento y sus restricciones están en
[`docs/deployment/escalado-local.md`](../deployment/escalado-local.md).

## Trabajo que sigue abierto

La publicación de los cambios locales y las tareas académicas #111–#117 siguen
pendientes. Las issues se cerrarán con evidencias accesibles de su resultado;
las fechas objetivo no representan fechas de finalización real. Una validación
local no equivale a una release ni a un despliegue de producción.
