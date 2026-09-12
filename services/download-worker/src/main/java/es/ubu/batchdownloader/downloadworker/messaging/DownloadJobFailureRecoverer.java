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
 * Al agotar reintentos, comunica a Core un fallo terminal antes de rechazar el comando; conserva el
 * mensaje para otra entrega si falta capacidad o no pudo publicar ese resultado.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.messaging.DownloadJobListener
 * @see es.ubu.batchdownloader.downloadworker.ports.EventPublisher
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y operación del worker
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
     * Conecta lectura del comando, publicación del fallo y reloj de recuperación.
     *
     * @param objectMapper Lector JSON del comando original cuando debe producirse un fallo
     *     terminal.
     * @param eventPublisher Transporte de eventos que comunica el fallo terminal a Core.
     * @param clock Reloj común que fecha resultados y señales de salud.
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
     * Distingue capacidad de fallo definitivo, recupera el trabajo del comando y publica un evento
     * determinista de fallo; solo después desvía el mensaje a rechazo.
     *
     * @param message Mensaje AMQP original cuyos intentos de procesamiento se agotaron.
     * @param cause Cadena de fallos que explica por qué el consumidor no pudo completar el mensaje.
     * @throws org.springframework.amqp.ImmediateRequeueAmqpException si falta capacidad o no se
     *     puede publicar el fallo terminal.
     * @throws org.springframework.amqp.AmqpRejectAndDontRequeueException si el comando no puede
     *     deserializarse o ya se publicó su resultado terminal.
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
     * Deriva la identidad del fallo por agotamiento a partir del trabajo, tipo y código estables.
     *
     * @param jobId UUID del trabajo al que se aplica el evento.
     * @return UUID reproducible entre intentos de recuperación.
     */
    private UUID deterministicEventId(UUID jobId) {
        return UUID.nameUUIDFromBytes(
                (jobId + ":" + EventTypes.JOB_FAILED + ":" + FAILURE_CODE)
                        .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Recorre las causas buscando storage_busy para conservar la condición como temporal.
     *
     * @param cause Cadena de fallos que explica por qué el consumidor no pudo completar el mensaje.
     * @return true si algún fallo de la cadena indica falta de capacidad.
     */
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
