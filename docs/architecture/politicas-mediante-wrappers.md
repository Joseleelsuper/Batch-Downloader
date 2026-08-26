# Políticas de ejecución mediante wrappers

## Criterio

La lógica que define el resultado funcional permanece en el caso de uso. Las medidas derivadas de
desconfianza, recursos finitos u observabilidad se componen en el borde mediante decorators,
dependencias del framework o adaptadores equivalentes.

No se añaden reintentos genéricos. Un reintento sólo pertenece a la capa que conoce la semántica de
idempotencia y el contrato del sistema remoto; por ejemplo, la política ya existente del listener de
Rabbit.

## Composición aplicada

| Componente | Cadena efectiva | Responsabilidad que permanece dentro |
| --- | --- | --- |
| `download-worker` / AMQP | validación → inbox/lease → `DownloadJobProcessor` | estados del job, resolución, empaquetado, publicación y resultado parcial |
| `download-worker` / descarga | métricas → cleanup → integridad → redirects y lectura acotada → HTTPS/SSRF por salto → JDK | producir el fichero y su SHA-256 |
| `core-api` / HTTP interno | métricas opcionales → timeout → token de servicio → JDK | rutas, JSON y mapeo de cada respuesta al contrato del dominio |
| `api/scraper` / HTTP externo | redirects explícitos → HTTPS/DNS/SSRF por salto → errores de transporte → HTTPX; lector acotado dentro del stream | recuperar la respuesta permitida |
| `api/scraper` / rutas internas | `Depends(require_internal_service_token)` → endpoint | operación propia del endpoint |
| `webapp` | error JSON → reintento CSRF único → CSRF → credenciales → timeout opcional → `fetch` | función de API y tipos de respuesta |
| `notification-service` | Rabbit retry → handler/inbox → métricas de envío → router SMTP/Resend | idempotencia del evento y elección funcional de canal |
| `semantic-service` | token interno → capacidad → timeout de embedding → búsqueda | selección de modelo, candidatos y resultado semántico |
| `translation-service` | métricas hit/miss → catálogo JSON en memoria | lookup exacto del locale |

## Invariantes de seguridad

- Toda URL externa del worker y del scraper se valida inmediatamente antes de cada salto. Una URL
  inicial segura no autoriza automáticamente el destino de un redirect.
- El máximo de bytes se comprueba tanto con `Content-Length` como durante el streaming.
- Un rechazo de integridad elimina el fichero parcial; el fallo de cleanup no sustituye al error
  original.
- Los tokens, URLs, destinatarios, IDs y rutas dinámicas no se usan como etiquetas de métricas.
- Las interrupciones Java restauran el flag del hilo antes de traducirse al contrato de cada cliente.
- Las métricas son opcionales: la ausencia de `MeterRegistry` no desactiva autenticación ni timeout.

## Decisiones deliberadas

- `semantic-service` usa dependencias invocables de FastAPI en lugar de decorators Python propios;
  conservan el orden visible en la declaración de la ruta y liberan el semáforo en `finally`.
- `translation-service` no normaliza locales ni introduce fallback. Ambas decisiones cambiarían el
  contrato funcional actual; sólo se añadió observabilidad alrededor del catálogo en memoria.
- El `SecurityContext` del login permanece en la capa web de Spring Security. No se convierte en
  una responsabilidad del dominio.
- La clasificación de estados HTTP y errores JSON permanece en cada cliente de `core-api`, porque
  sus códigos públicos y estrategias de degradación no son intercambiables.

## Reglas para nuevas capas

1. Cada wrapper debe depender del mismo puerto que implementa y delegar una sola vez.
2. El orden de composición forma parte del contrato y debe cubrirse con pruebas.
3. Las políticas que trabajan durante un stream deben ejecutarse antes de cerrar su respuesta.
4. La compatibilidad pública se conserva mediante fachadas cuando una implementación existente ya
   sea usada por configuración o pruebas.
5. Un wrapper no debe capturar excepciones funcionales para convertirlas en éxitos silenciosos.
