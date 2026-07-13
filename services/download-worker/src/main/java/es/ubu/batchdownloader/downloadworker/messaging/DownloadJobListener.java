package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DownloadJobListener {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobListener.class);

    private final Validator validator;
    private final InboxRepository inbox;
    private final DownloadJobProcessor processor;
    private final DownloadProperties properties;

    public DownloadJobListener(
            Validator validator,
            InboxRepository inbox,
            DownloadJobProcessor processor,
            DownloadProperties properties) {
        this.validator = validator;
        this.inbox = inbox;
        this.processor = processor;
        this.properties = properties;
    }

    @RabbitListener(
            queues = "${download-worker.messaging.input-queue}",
            containerFactory = "downloadRabbitListenerContainerFactory")
    public void receive(DownloadJobRequestedEvent event) {
        validate(event);
        if (!inbox.tryStart(event.eventId(), properties.inboxLease())) {
            LOGGER.info(
                    "Ignoring duplicate download event eventId={} jobId={}",
                    event.eventId(), event.payload().jobId());
            return;
        }
        try {
            processor.process(event);
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

    private void validate(DownloadJobRequestedEvent event) {
        if (event == null) {
            throw new AmqpRejectAndDontRequeueException("null_download_event");
        }
        Set<ConstraintViolation<DownloadJobRequestedEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new AmqpRejectAndDontRequeueException("invalid_download_event");
        }
        if (!EventTypes.JOB_REQUESTED.equals(event.type())
                || event.schemaVersion() != EventTypes.CURRENT_VERSION) {
            throw new AmqpRejectAndDontRequeueException("unsupported_download_event_version");
        }
    }
}
