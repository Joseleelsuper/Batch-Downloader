package es.ubu.batchdownloader.downloadworker.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agrupa los sobres y cargas que conectan admisión en Core, procesamiento del worker y
 * actualización de resultados, conservando selección exacta y correlación.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.domain.EventTypes
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadEventEmitter
 * @since 0.1.0
 * @version 0.1.0
 * @category Contratos de descarga
 */
public final class DownloadEvents {
    /**
     * Impide instanciar el contenedor de contratos de eventos de descarga.
     */
    private DownloadEvents() {
    }

    /**
     * Transporta una solicitud de procesamiento con restricciones declarativas que se comprueban
     * antes de reservar su evento en el inbox.
     *
     * @param eventId UUID estable del evento para deduplicar entregas repetidas.
     * @param type Tipo del evento que determina el contrato de su carga.
     * @param schemaVersion Versión del esquema del evento, independiente de la versión del
     *     servicio.
     * @param occurredAt Instante de creación o transición comunicado por el productor.
     * @param correlationId Identificador compartido por los eventos del mismo flujo de descarga.
     * @param causationId Identificador del evento causante; puede faltar para una solicitud
     *     inicial.
     * @param payload Carga tipada con el trabajo y los datos específicos de la transición.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadJobRequestedEvent(
            @NotNull UUID eventId,
            @NotBlank String type,
            @Positive int schemaVersion,
            @NotNull Instant occurredAt,
            @NotBlank String correlationId,
            String causationId,
            @NotNull @Valid DownloadJobPayload payload) {
    }

    /**
     * Conserva el trabajo ya admitido y su selección ordenada de elementos para que el worker
     * procese exactamente ese conjunto.
     *
     * @param jobId UUID del trabajo persistido por Core antes de publicar la solicitud.
     * @param items Selección no vacía de hasta cien elementos; conserva su orden original.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadJobPayload(
            @NotNull UUID jobId,
            @NotEmpty @Size(max = 100) List<@Valid DownloadItemRequest> items) {
    }

    /**
     * Distingue un instalador exacto de una alternativa manual mediante la presencia de sourceRef,
     * sin transportar la URI final privada.
     *
     * @param itemId UUID del elemento dentro del trabajo que se procesa.
     * @param appId UUID de la aplicación a la que pertenece el elemento.
     * @param sourceRef UUID exacto del instalador seleccionado; null representa un elemento
     *     exclusivamente manual.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadItemRequest(
            @NotNull UUID itemId,
            @NotNull UUID appId,
            UUID sourceRef) {
    }

    /**
     * Notifica una transición individual de descarga con identidad deduplicable y vínculo al
     * comando que la causó.
     *
     * @param eventId UUID estable del evento para deduplicar entregas repetidas.
     * @param type Tipo del evento que determina el contrato de su carga.
     * @param schemaVersion Versión del esquema del evento, independiente de la versión del
     *     servicio.
     * @param occurredAt Instante de creación o transición comunicado por el productor.
     * @param correlationId Identificador compartido por los eventos del mismo flujo de descarga.
     * @param causationId Identificador del evento causante; puede faltar para una solicitud
     *     inicial.
     * @param payload Carga tipada con el trabajo y los datos específicos de la transición.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadJobProgressedEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadProgressPayload payload) {
    }

    /**
     * Actualiza estado y bytes de un elemento sin exigir que hayan terminado los demás instaladores
     * del trabajo.
     *
     * @param jobId UUID del trabajo persistido por Core antes de publicar la solicitud.
     * @param itemId UUID del elemento dentro del trabajo que se procesa.
     * @param status Estado del elemento o resultado conjunto descrito por la carga concreta.
     * @param bytesDownloaded Cantidad de bytes transferidos del elemento.
     * @param sizeBytes Tamaño esperado o final en bytes; puede ser null en progreso si se
     *     desconoce.
     * @param sha256 SHA-256 hexadecimal del contenido verificado, o null antes de completarlo.
     * @param errorCode Código seguro del fallo, sin incluir URLs privadas ni contenido del
     *     proveedor.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadProgressPayload(
            UUID jobId,
            UUID itemId,
            String status,
            long bytesDownloaded,
            Long sizeBytes,
            String sha256,
            String errorCode) {
    }

    /**
     * Notifica que ZIP y manifiesto están almacenados y que Core puede ofrecer el resultado al
     * propietario.
     *
     * @param eventId UUID estable del evento para deduplicar entregas repetidas.
     * @param type Tipo del evento que determina el contrato de su carga.
     * @param schemaVersion Versión del esquema del evento, independiente de la versión del
     *     servicio.
     * @param occurredAt Instante de creación o transición comunicado por el productor.
     * @param correlationId Identificador compartido por los eventos del mismo flujo de descarga.
     * @param causationId Identificador del evento causante; puede faltar para una solicitud
     *     inicial.
     * @param payload Carga tipada con el trabajo y los datos específicos de la transición.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadJobReadyEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadReadyPayload payload) {
    }

    /**
     * Comunica falta temporal de capacidad conservando el trabajo pendiente y una previsión de
     * reintento.
     *
     * @param eventId UUID estable del evento para deduplicar entregas repetidas.
     * @param type Tipo del evento que determina el contrato de su carga.
     * @param schemaVersion Versión del esquema del evento, independiente de la versión del
     *     servicio.
     * @param occurredAt Instante de creación o transición comunicado por el productor.
     * @param correlationId Identificador compartido por los eventos del mismo flujo de descarga.
     * @param causationId Identificador del evento causante; puede faltar para una solicitud
     *     inicial.
     * @param payload Carga tipada con el trabajo y los datos específicos de la transición.
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadJobDeferredEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadDeferredPayload payload) {
    }

    /**
     * Explica una espera de capacidad sin convertirla en fallo terminal del trabajo.
     *
     * @param jobId UUID del trabajo persistido por Core antes de publicar la solicitud.
     * @param waitReason Código que explica por qué se aplaza el trabajo sin declararlo fallido.
     * @param retryAt Instante previsto para volver a intentar el trabajo aplazado.
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadDeferredPayload(
            UUID jobId,
            String waitReason,
            Instant retryAt) {
    }

    /**
     * Conserva ubicación privada del ZIP, integridad, recuentos y vigencia para que Core gestione
     * su disponibilidad pública.
     *
     * @param jobId UUID del trabajo persistido por Core antes de publicar la solicitud.
     * @param status READY, PARTIAL o MANUAL_ONLY según el contenido entregable.
     * @param objectKey Clave del ZIP confirmado en almacenamiento; Core genera el acceso temporal.
     * @param sizeBytes Longitud del ZIP completo confirmado, en bytes.
     * @param storageBytes Ocupación confirmada de ZIP y manifiesto, tras borrar todos los temporales.
     * @param sha256 SHA-256 hexadecimal calculado durante la escritura del ZIP.
     * @param successfulItems Cantidad de instaladores completos incluidos en el ZIP.
     * @param failedItems Cantidad de elementos sin instalador completado, incluidos los que ofrecen
     *     alternativa manual.
     * @param expiresAt Instante hasta el que se declara disponible el resultado entregable.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadReadyPayload(
            UUID jobId,
            String status,
            String objectKey,
            long sizeBytes,
            long storageBytes,
            String sha256,
            int successfulItems,
            int failedItems,
            Instant expiresAt) {
    }

    /**
     * Comunica que no puede entregarse un resultado útil del trabajo y permite a Core registrar su
     * fallo terminal.
     *
     * @param eventId UUID estable del evento para deduplicar entregas repetidas.
     * @param type Tipo del evento que determina el contrato de su carga.
     * @param schemaVersion Versión del esquema del evento, independiente de la versión del
     *     servicio.
     * @param occurredAt Instante de creación o transición comunicado por el productor.
     * @param correlationId Identificador compartido por los eventos del mismo flujo de descarga.
     * @param causationId Identificador del evento causante; puede faltar para una solicitud
     *     inicial.
     * @param payload Carga tipada con el trabajo y los datos específicos de la transición.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadJobFailedEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            DownloadFailedPayload payload) {
    }

    /**
     * Identifica el trabajo fallido y el motivo seguro que se mostrará junto a su recuento de
     * elementos.
     *
     * @param jobId UUID del trabajo persistido por Core antes de publicar la solicitud.
     * @param errorCode Código seguro del fallo, sin incluir URLs privadas ni contenido del
     *     proveedor.
     * @param failedItems Cantidad de elementos sin instalador completado, incluidos los que ofrecen
     *     alternativa manual.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Contratos de descarga
     */
    public record DownloadFailedPayload(UUID jobId, String errorCode, int failedItems) {
    }
}
