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
 * Consume cancelaciones en una cola independiente, valida su contrato y las deduplica antes de
 * marcar el trabajo y cancelar sus tareas en vuelo.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadCancellationRegistry
 * @see es.ubu.batchdownloader.downloadworker.ports.InboxRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y operación del worker
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
     * Conecta validación del ciclo de reserva con cancelación y duración del inbox.
     *
     * @param inbox Reserva y deduplicación de los eventos recibidos.
     * @param cancellations Registro que solicita parada de tareas por UUID de trabajo.
     * @param properties Configuración que aporta la duración de reserva del inbox.
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
     * Valida el evento, intenta reservarlo y solicita parada antes de confirmarlo; si falla el
     * procesamiento libera la reserva y propaga el fallo.
     *
     * @param event Sobre recibido con identidad y carga del trabajo solicitado o cancelado.
     */
    @RabbitListener(
            queues = "${download-worker.messaging.cancellation-queue}",
            containerFactory = "downloadCancellationRabbitListenerContainerFactory")
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
     * Exige evento, trabajo e identidad presentes y exactamente el tipo y versión de cancelación
     * admitidos.
     *
     * @param event Sobre recibido con identidad y carga del trabajo solicitado o cancelado.
     * @throws org.springframework.amqp.AmqpRejectAndDontRequeueException si faltan identificadores
     *     o no coincide el contrato del evento.
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
     * Recibe el sobre correlacionado de una solicitud de cancelación de descarga.
     *
     * @param eventId UUID estable del evento utilizado para deduplicar entregas.
     * @param type Nombre del contrato de evento; el consumidor comprueba el tipo esperado.
     * @param schemaVersion Versión del contrato del sobre, comprobada antes de procesar el evento.
     * @param occurredAt Instante en el que el productor creó el evento.
     * @param correlationId Identificador que relaciona el evento con su flujo de origen.
     * @param causationId Identificador del evento que causó este mensaje; puede faltar en una
     *     acción inicial.
     * @param payload Carga tipada del evento que identifica el trabajo y el cambio solicitado.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Mensajería y operación del worker
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
     * Identifica el trabajo cuya ejecución debe detenerse cooperativamente.
     *
     * @param jobId UUID del trabajo al que se aplica el evento.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Mensajería y operación del worker
     */
    public record CancellationPayload(UUID jobId) {
    }
}
