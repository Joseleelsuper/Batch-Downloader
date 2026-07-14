package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadCancellationRegistry;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Records cancellation commands before interrupting any currently running item tasks. */
@Component
public class DownloadCancellationListener {
    private final InboxRepository inbox;
    private final DownloadCancellationRegistry cancellations;
    private final DownloadProperties properties;

    public DownloadCancellationListener(
            InboxRepository inbox,
            DownloadCancellationRegistry cancellations,
            DownloadProperties properties) {
        this.inbox = inbox;
        this.cancellations = cancellations;
        this.properties = properties;
    }

    @RabbitListener(
            queues = "${download-worker.messaging.cancellation-queue}",
            containerFactory = "downloadRabbitListenerContainerFactory")
    public void receive(CancellationRequestedEvent event) {
        validate(event);
        if (!inbox.tryStart(event.eventId(), properties.inboxLease())) {
            return;
        }
        try {
            cancellations.cancel(event.payload().jobId());
            inbox.complete(event.eventId());
        } catch (RuntimeException exception) {
            inbox.release(event.eventId());
            throw exception;
        }
    }

    private void validate(CancellationRequestedEvent event) {
        if (event == null
                || event.eventId() == null
                || event.payload() == null
                || event.payload().jobId() == null
                || !EventTypes.JOB_CANCEL_REQUESTED.equals(event.type())
                || event.schemaVersion() != EventTypes.CURRENT_VERSION) {
            throw new AmqpRejectAndDontRequeueException("invalid_download_cancellation_event");
        }
    }

    public record CancellationRequestedEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            CancellationPayload payload) {
    }

    public record CancellationPayload(UUID jobId) {
    }
}
