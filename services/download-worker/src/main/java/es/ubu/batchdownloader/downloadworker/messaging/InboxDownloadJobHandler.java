package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Aplica idempotencia con lease alrededor del procesamiento del evento. */
public final class InboxDownloadJobHandler implements DownloadJobHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(InboxDownloadJobHandler.class);
    private final InboxRepository inbox;
    private final DownloadProperties properties;
    private final DownloadJobHandler delegate;

    /** Inicializa el wrapper de idempotencia. */
    public InboxDownloadJobHandler(
            InboxRepository inbox,
            DownloadProperties properties,
            DownloadJobHandler delegate) {
        this.inbox = inbox;
        this.properties = properties;
        this.delegate = delegate;
    }

    /** {@inheritDoc} */
    @Override
    public void handle(DownloadJobRequestedEvent event) {
        if (!inbox.tryStart(event.eventId(), properties.inboxLease())) {
            LOGGER.info(
                    "Ignoring duplicate download event eventId={} jobId={}",
                    event.eventId(), event.payload().jobId());
            return;
        }
        try {
            delegate.handle(event);
            inbox.complete(event.eventId());
            LOGGER.info(
                    "Download job completed eventId={} jobId={}",
                    event.eventId(), event.payload().jobId());
        } catch (RuntimeException exception) {
            inbox.release(event.eventId());
            LOGGER.warn(
                    "Download job failed and will be retried eventId={} jobId={} error={}",
                    event.eventId(),
                    event.payload().jobId(),
                    exception.getClass().getSimpleName());
            throw exception;
        }
    }
}
