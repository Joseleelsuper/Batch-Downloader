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

/**
 * Procesa los eventos recibidos por {@code DownloadCancellationListener}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class DownloadCancellationListener {
    /**
     * Estado {@code inbox} mantenido por {@code DownloadCancellationListener}.
     */
    private final InboxRepository inbox;
    /**
     * Estado {@code cancellations} mantenido por {@code DownloadCancellationListener}.
     */
    private final DownloadCancellationRegistry cancellations;
    /**
     * Estado {@code properties} mantenido por {@code DownloadCancellationListener}.
     */
    private final DownloadProperties properties;

    /**
     * Inicializa una instancia de {@code DownloadCancellationListener}.
     *
     * @param inbox Valor de {@code inbox} utilizado por la operación.
     * @param cancellations Valor de {@code cancellations} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public DownloadCancellationListener(
            InboxRepository inbox,
            DownloadCancellationRegistry cancellations,
            DownloadProperties properties) {
        this.inbox = inbox;
        this.cancellations = cancellations;
        this.properties = properties;
    }

    /**
     * Ejecuta la operación {@code receive}.
     *
     * @param event Evento que debe procesarse.
     */
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

    /**
     * Valida los datos recibidos mediante {@code validate}.
     *
     * @param event Evento que debe procesarse.
     * @throws AmqpRejectAndDontRequeueException Si no puede completarse la operación bajo las
     *     condiciones requeridas.
     */
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

    /**
     * Representa los datos inmutables de {@code CancellationRequestedEvent}.
     *
     * @param eventId Valor de {@code eventId} incluido en el record.
     * @param type Valor de {@code type} incluido en el record.
     * @param schemaVersion Valor de {@code schemaVersion} incluido en el record.
     * @param occurredAt Valor de {@code occurredAt} incluido en el record.
     * @param correlationId Valor de {@code correlationId} incluido en el record.
     * @param causationId Valor de {@code causationId} incluido en el record.
     * @param payload Valor de {@code payload} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record CancellationRequestedEvent(
            UUID eventId,
            String type,
            int schemaVersion,
            Instant occurredAt,
            String correlationId,
            String causationId,
            CancellationPayload payload) {
    }

    /**
     * Representa los datos inmutables de {@code CancellationPayload}.
     *
     * @param jobId Valor de {@code jobId} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record CancellationPayload(UUID jobId) {
    }
}
