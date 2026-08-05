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

/**
 * Implementa el componente {@code DownloadJobFailureRecoverer}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class DownloadJobFailureRecoverer implements MessageRecoverer {
    /**
     * Constante que define {@code LOGGER}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobFailureRecoverer.class);
    /**
     * Constante que define {@code FAILURE_CODE}.
     */
    private static final String FAILURE_CODE = "download_job_processing_failed";

    /**
     * Dependencia {@code objectMapper} utilizada por {@code DownloadJobFailureRecoverer}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Dependencia {@code eventPublisher} utilizada por {@code DownloadJobFailureRecoverer}.
     */
    private final EventPublisher eventPublisher;
    /**
     * Estado {@code clock} mantenido por {@code DownloadJobFailureRecoverer}.
     */
    private final Clock clock;

    /**
     * Inicializa una instancia de {@code DownloadJobFailureRecoverer}.
     *
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param eventPublisher Valor de {@code eventPublisher} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     */
    public DownloadJobFailureRecoverer(
            ObjectMapper objectMapper,
            EventPublisher eventPublisher,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Recupera los elementos afectados mediante {@code recover}.
     *
     * @param message Mensaje que debe procesarse.
     * @param cause Valor de {@code cause} utilizado por la operación.
     * @throws AmqpRejectAndDontRequeueException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     * @throws ImmediateRequeueAmqpException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
    @Override
    public void recover(Message message, Throwable cause) {
        if (causedByStorageCapacity(cause)) {
            throw new ImmediateRequeueAmqpException(
                    "Temporary storage capacity is unavailable", cause);
        }
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

    /**
     * Ejecuta la operación {@code deterministicEventId}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @return Resultado producido por {@code deterministicEventId}.
     */
    private UUID deterministicEventId(UUID jobId) {
        return UUID.nameUUIDFromBytes(
                (jobId + ":" + EventTypes.JOB_FAILED + ":" + FAILURE_CODE)
                        .getBytes(StandardCharsets.UTF_8));
    }

    /** Mantiene en cola los trabajos que solo esperan espacio temporal en el SSD. */
    private boolean causedByStorageCapacity(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if ("storage_busy".equals(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
