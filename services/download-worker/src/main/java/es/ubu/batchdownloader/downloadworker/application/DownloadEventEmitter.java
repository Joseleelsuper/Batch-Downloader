package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadFailedPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadDeferredPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobDeferredEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobFailedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobProgressedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobReadyEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadProgressPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadReadyPayload;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore.StoredArtifact;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Publica sobres de progreso, disponibilidad, fallo y espera con UUID deterministas para que Core
 * pueda deduplicar entregas repetidas sin perder la correlación del trabajo.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.EventPublisher
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Resultados y empaquetado
 */
public final class DownloadEventEmitter {
    private final EventPublisher publisher;
    private final StorageProperties storage;
    private final Clock clock;
    private final es.ubu.batchdownloader.downloadworker.ports.InboxRepository inbox;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    /**
     * Conecta publicación, vigencia de resultados y reloj de eventos.
     *
     * @param publisher Transporte que publica los sobres de progreso y resultado para Core.
     * @param storage Configuración de vigencia de resultados en almacenamiento.
     * @param clock Reloj para fechar el progreso y las decisiones del coordinador.
     */
    public DownloadEventEmitter(EventPublisher publisher, StorageProperties storage, Clock clock,
            es.ubu.batchdownloader.downloadworker.ports.InboxRepository inbox,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.publisher = publisher;
        this.storage = storage;
        this.clock = clock;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    /**
     * Publica el resultado entregable con clave, integridad y recuentos; acota la vigencia
     * configurada a un máximo de siete días.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param status Resultado conjunto READY, PARTIAL o MANUAL_ONLY que se incluirá en el
     *     manifiesto.
     * @param successfulItems Cantidad de instaladores descargados incluidos en el ZIP.
     * @param failedItems Cantidad de elementos fallidos; la excepción de fallo total la acota a un
     *     mínimo de uno.
     * @param zip Tamaño y SHA-256 calculados al almacenar el ZIP completo.
     * @param zipObjectKey Clave del ZIP confirmado dentro del almacén del worker.
     * @param storageBytes Bytes confirmados de ZIP y manifiesto tras limpiar temporales.
     */
    void ready(
            DownloadJobRequestedEvent event,
            String status,
            int successfulItems,
            int failedItems,
            StoredArtifact zip,
            String zipObjectKey,
            long storageBytes) {
        Instant occurredAt = clock.instant();
        Duration ttl = storage.presignedUrlTtl().compareTo(Duration.ofDays(7)) > 0
                ? Duration.ofDays(7)
                : storage.presignedUrlTtl();
        DownloadReadyPayload payload = new DownloadReadyPayload(
                event.payload().jobId(),
                status,
                zipObjectKey,
                zip.sizeBytes(),
                storageBytes,
                zip.sha256(),
                successfulItems,
                failedItems,
                occurredAt.plus(ttl));
        DownloadJobReadyEvent ready = new DownloadJobReadyEvent(
                eventId(event.eventId(), EventTypes.JOB_READY, "bundle"),
                EventTypes.JOB_READY,
                EventTypes.CURRENT_VERSION,
                occurredAt,
                event.correlationId(),
                event.eventId().toString(),
                payload);
        try {
            inbox.saveReady(event.eventId(), event.payload().jobId(), mapper.writeValueAsString(ready));
        } catch (java.io.IOException exception) {
            throw new InfrastructureException("ready_receipt_failed", exception);
        }
        publisher.publish(EventTypes.JOB_READY_ROUTING_KEY, ready);
    }

    DownloadReadyPayload replayReady(DownloadJobRequestedEvent command) {
        String json = inbox.pendingReady(command.eventId());
        if (json == null) return null;
        try {
            DownloadJobReadyEvent ready = mapper.readValue(json, DownloadJobReadyEvent.class);
            if (!ready.payload().jobId().equals(command.payload().jobId())
                    || !command.eventId().toString().equals(ready.causationId())) {
                throw new IllegalStateException("ready_receipt_mismatch");
            }
            publisher.publish(EventTypes.JOB_READY_ROUTING_KEY, ready);
            return ready.payload();
        } catch (java.io.IOException exception) {
            throw new InfrastructureException("ready_receipt_invalid", exception);
        }
    }

    void clearReady(UUID jobId) { inbox.clearReady(jobId); }
    void rememberJob(UUID eventId, UUID jobId) { inbox.rememberJob(eventId, jobId); }
    java.util.Set<UUID> trackedJobs() { return inbox.trackedJobs(); }

    /**
     * Publica la transición de un elemento con identidad derivada del trabajo, elemento y estado,
     * conservando bytes e integridad disponibles.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param occurredAt Instante de la transición individual que se publica.
     * @param itemId UUID del elemento que cambió de estado.
     * @param status Resultado conjunto READY, PARTIAL o MANUAL_ONLY que se incluirá en el
     *     manifiesto.
     * @param bytesDownloaded Bytes transferidos del elemento en esta actualización.
     * @param sizeBytes Tamaño esperado o final del elemento, en bytes; null si se desconoce.
     * @param sha256 Huella del instalador completado; null antes de verificarlo.
     * @param errorCode Código seguro del fallo individual; null si no se está comunicando un
     *     rechazo.
     */
    void progress(
            DownloadJobRequestedEvent event,
            Instant occurredAt,
            UUID itemId,
            String status,
            long bytesDownloaded,
            Long sizeBytes,
            String sha256,
            String errorCode) {
        DownloadProgressPayload payload = new DownloadProgressPayload(
                event.payload().jobId(), itemId, status, bytesDownloaded, sizeBytes, sha256, errorCode);
        publisher.publish(EventTypes.JOB_PROGRESSED_ROUTING_KEY, new DownloadJobProgressedEvent(
                eventId(
                        event.eventId(),
                        EventTypes.JOB_PROGRESSED,
                        itemId + ":" + status.toLowerCase(Locale.ROOT)),
                EventTypes.JOB_PROGRESSED,
                EventTypes.CURRENT_VERSION,
                occurredAt,
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

    /**
     * Publica el fallo terminal del trabajo con recuento de al menos un elemento y UUID derivado de
     * su código de fallo.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param code Código estable del rechazo que puede incluirse en el resultado individual.
     * @param failedItems Cantidad de elementos fallidos; la excepción de fallo total la acota a un
     *     mínimo de uno.
     */
    void failed(DownloadJobRequestedEvent event, String code, int failedItems) {
        DownloadFailedPayload payload = new DownloadFailedPayload(
                event.payload().jobId(), code, Math.max(1, failedItems));
        publisher.publish(EventTypes.JOB_FAILED_ROUTING_KEY, new DownloadJobFailedEvent(
                eventId(event.eventId(), EventTypes.JOB_FAILED, code),
                EventTypes.JOB_FAILED,
                EventTypes.CURRENT_VERSION,
                clock.instant(),
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

    /**
     * Publica una espera no terminal con motivo y próximo intento; distingue reprogramaciones
     * mediante el segundo de retryAt.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param reason Motivo estable de aplazamiento, utilizado por Core para explicar la espera.
     * @param retryAt Instante a partir del cual se prevé volver a intentar el trabajo.
     */
    void deferred(DownloadJobRequestedEvent event, String reason, Instant retryAt) {
        Instant occurredAt = clock.instant();
        DownloadDeferredPayload payload = new DownloadDeferredPayload(
                event.payload().jobId(), reason, retryAt);
        publisher.publish(EventTypes.JOB_DEFERRED_ROUTING_KEY, new DownloadJobDeferredEvent(
                eventId(
                        event.eventId(),
                        EventTypes.JOB_DEFERRED,
                        Long.toString(retryAt.getEpochSecond())),
                EventTypes.JOB_DEFERRED,
                EventTypes.CURRENT_VERSION,
                occurredAt,
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

    /**
     * Deriva un UUID reproducible del intento, tipo y discriminador codificados en UTF-8.
     *
     * @param attemptId UUID del comando que autoriza este intento FIFO.
     * @param type Tipo de evento del contrato de descargas.
     * @param discriminator Dato estable que distingue transiciones del mismo trabajo y tipo.
     * @return misma identidad para la misma transición lógica entre reintentos.
     */
    private UUID eventId(UUID attemptId, String type, String discriminator) {
        return UUID.nameUUIDFromBytes(
                (attemptId + ":" + type + ":" + discriminator).getBytes(StandardCharsets.UTF_8));
    }
}
