package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadFailedPayload;
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

/** Publica los eventos del trabajo con identificadores deterministas. */
final class DownloadEventEmitter {
    private final EventPublisher publisher;
    private final StorageProperties storage;
    private final Clock clock;

    DownloadEventEmitter(EventPublisher publisher, StorageProperties storage, Clock clock) {
        this.publisher = publisher;
        this.storage = storage;
        this.clock = clock;
    }

    void ready(
            DownloadJobRequestedEvent event,
            String status,
            int successfulItems,
            int failedItems,
            StoredArtifact zip,
            String zipObjectKey) {
        Instant occurredAt = clock.instant();
        Duration ttl = storage.presignedUrlTtl().compareTo(Duration.ofDays(7)) > 0
                ? Duration.ofDays(7)
                : storage.presignedUrlTtl();
        DownloadReadyPayload payload = new DownloadReadyPayload(
                event.payload().jobId(),
                status,
                zipObjectKey,
                zip.sizeBytes(),
                zip.sha256(),
                successfulItems,
                failedItems,
                occurredAt.plus(ttl));
        publisher.publish(EventTypes.JOB_READY_ROUTING_KEY, new DownloadJobReadyEvent(
                eventId(event.payload().jobId(), EventTypes.JOB_READY, "bundle"),
                EventTypes.JOB_READY,
                EventTypes.CURRENT_VERSION,
                occurredAt,
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

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
                        event.payload().jobId(),
                        EventTypes.JOB_PROGRESSED,
                        itemId + ":" + status.toLowerCase(Locale.ROOT)),
                EventTypes.JOB_PROGRESSED,
                EventTypes.CURRENT_VERSION,
                occurredAt,
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

    void failed(DownloadJobRequestedEvent event, String code, int failedItems) {
        DownloadFailedPayload payload = new DownloadFailedPayload(
                event.payload().jobId(), code, Math.max(1, failedItems));
        publisher.publish(EventTypes.JOB_FAILED_ROUTING_KEY, new DownloadJobFailedEvent(
                eventId(event.payload().jobId(), EventTypes.JOB_FAILED, code),
                EventTypes.JOB_FAILED,
                EventTypes.CURRENT_VERSION,
                clock.instant(),
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

    private UUID eventId(UUID jobId, String type, String discriminator) {
        return UUID.nameUUIDFromBytes(
                (jobId + ":" + type + ":" + discriminator).getBytes(StandardCharsets.UTF_8));
    }
}
