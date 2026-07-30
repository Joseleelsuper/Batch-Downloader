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

/**
 * Procesa los eventos recibidos por {@code DownloadJobListener}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class DownloadJobListener {
    /**
     * Constante que define {@code LOGGER}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobListener.class);

    /**
     * Estado {@code validator} mantenido por {@code DownloadJobListener}.
     */
    private final Validator validator;
    /**
     * Estado {@code inbox} mantenido por {@code DownloadJobListener}.
     */
    private final InboxRepository inbox;
    /**
     * Estado {@code processor} mantenido por {@code DownloadJobListener}.
     */
    private final DownloadJobProcessor processor;
    /**
     * Estado {@code properties} mantenido por {@code DownloadJobListener}.
     */
    private final DownloadProperties properties;

    /**
     * Inicializa una instancia de {@code DownloadJobListener}.
     *
     * @param validator Valor de {@code validator} utilizado por la operación.
     * @param inbox Valor de {@code inbox} utilizado por la operación.
     * @param processor Valor de {@code processor} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
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

    /**
     * Ejecuta la operación {@code receive}.
     *
     * @param event Evento que debe procesarse.
     */
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

    /**
     * Valida los datos recibidos mediante {@code validate}.
     *
     * @param event Evento que debe procesarse.
     * @throws AmqpRejectAndDontRequeueException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
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
