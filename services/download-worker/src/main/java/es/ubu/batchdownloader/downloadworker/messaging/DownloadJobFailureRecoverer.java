package es.ubu.batchdownloader.downloadworker.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadFailedPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobFailedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;

public final class DownloadJobFailureRecoverer implements MessageRecoverer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobFailureRecoverer.class);
    private static final String FAILURE_CODE = "download_job_processing_failed";

    private final ObjectMapper objectMapper;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    public DownloadJobFailureRecoverer(
            ObjectMapper objectMapper,
            EventPublisher eventPublisher,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    public void recover(Message message, Throwable cause) {
        DownloadJobRequestedEvent requested;
        try {
            requested = objectMapper.readValue(message.getBody(), DownloadJobRequestedEvent.class);
        } catch (java.io.IOException invalidMessage) {
            LOGGER.error(
                    "Could not identify exhausted download command error={}",
                    invalidMessage.getClass().getSimpleName());
            throw new AmqpRejectAndDontRequeueException(FAILURE_CODE, invalidMessage);
        }

        UUID jobId = requested.payload().jobId();
        try {
            DownloadJobFailedEvent failed = new DownloadJobFailedEvent(
                    deterministicEventId(jobId),
                    EventTypes.JOB_FAILED,
                    EventTypes.CURRENT_VERSION,
                    clock.instant(),
                    requested.correlationId(),
                    requested.eventId().toString(),
                    new DownloadFailedPayload(
                            jobId,
                            FAILURE_CODE,
                            Math.max(1, requested.payload().items().size())));
            eventPublisher.publish(EventTypes.JOB_FAILED_ROUTING_KEY, failed);
            LOGGER.error(
                    "Download job exhausted retries jobId={} eventId={} error={}",
                    jobId,
                    requested.eventId(),
                    cause.getClass().getSimpleName());
        } catch (RuntimeException recoveryFailure) {
            LOGGER.error(
                    "Could not publish terminal download failure after retries jobId={} error={}",
                    jobId,
                    recoveryFailure.getClass().getSimpleName());
            throw new ImmediateRequeueAmqpException(
                    "Terminal failure must be published before dead-lettering", recoveryFailure);
        }
        throw new AmqpRejectAndDontRequeueException(FAILURE_CODE, cause);
    }

    private UUID deterministicEventId(UUID jobId) {
        return UUID.nameUUIDFromBytes(
                (jobId + ":" + EventTypes.JOB_FAILED + ":" + FAILURE_CODE)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
