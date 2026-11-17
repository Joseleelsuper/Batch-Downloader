# Auditoría administrativa

RNF-07 se acota a las operaciones administrativas por decisión del autor del
6 de noviembre de 2026, recogida en la
[issue #37](https://github.com/Joseleelsuper/Batch-Downloader/issues/37).
La aplicación utiliza sesiones y no ofrece claves API a sus usuarios. Este
requisito no incorpora un registro de llamadas a proveedores de IA.

Los controladores y servicios administrativos registran acciones de negocio
como `bundle.update`, `app.delete` o `scraper.command`. Cada entrada contiene
UUID del actor, acción, tipo e identificador de objetivo cuando procede, fecha
UTC y metadatos filtrados por el llamador. `safe_metadata.outcome` vale
`success` salvo que se indique otro resultado explícitamente. Los eventos con `result=failure` también reciben `outcome=failure`. Una solicitud
asíncrona aceptada acredita su aceptación, no la finalización del trabajo.

Un interceptor añade `admin.request.failed` para las mutaciones
`POST`, `PUT`, `PATCH` y `DELETE` de `/api/v1/admin/**` que llegan a MVC con
un administrador identificado y terminan con error. Guarda únicamente el
método, estado HTTP, plantilla de ruta y resultado `failure`; el objetivo solo
se copia si es un UUID canónico. No copia la URI real, query, cuerpo, mensaje
de excepción, nombre visible, correo, claves ni tokens. Las denegaciones de
los filtros de seguridad, por ejemplo CSRF, y las rutas sin correspondencia
MVC quedan fuera de este mecanismo.

Las entradas de negocio y los fallos HTTP son eventos distintos. Una petición
puede registrar una acción y fallar después; su suma no constituye un recuento
de operaciones únicas. El historial anterior a este cambio puede carecer de
`outcome` y no se reescribe.

La consulta `GET /api/v1/admin/audit?limit=100` exige rol `ADMIN` y limita el
resultado a 200 entradas, ordenadas por fecha descendente. La interfaz
administrativa presenta los mismos registros. No es un log público.

La escritura es de mejor esfuerzo: si falla MySQL o la serialización,
`AdminAuditService` incrementa `admin.audit.failures` y emite un aviso sin datos
del objetivo ni contenido de la petición. La operación principal conserva su
resultado. No se garantiza un registro persistido durante una caída de la
base de datos ni se afirma resistencia criptográfica frente a cambios de un
administrador de la propia base de datos.

`AdminAuditServiceTest` comprueba resultados y fallos de persistencia;
`AdminFailureAuditInterceptorTest` contrasta errores, exclusiones y ausencia
de texto privado; `AdminAppSecurityTest` comprueba la integración con MVC,
CSRF y la separación de roles al consultar el historial.
