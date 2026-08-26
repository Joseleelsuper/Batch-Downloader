package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException;
import es.ubu.batchdownloader.downloadworker.config.MessagingProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.operations.DownloadWorkerHeartbeat;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Procesa los eventos recibidos por {@code DownloadJobListener}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class DownloadJobListener {
    private final DownloadJobHandler handler;
    private final DownloadWorkerHeartbeat heartbeat;
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties messaging;

    /**
     * Inicializa una instancia de {@code DownloadJobListener}.
     *
     * @param handler cadena de políticas y caso de uso que procesa el evento.
     */
    @Autowired
    public DownloadJobListener(
            @Qualifier("downloadJobHandler") DownloadJobHandler handler,
            DownloadWorkerHeartbeat heartbeat,
            RabbitTemplate rabbitTemplate,
            MessagingProperties messaging) {
        this.handler = handler;
        this.heartbeat = heartbeat;
        this.rabbitTemplate = rabbitTemplate;
        this.messaging = messaging;
    }

    /** Conserva el constructor previo usado por consumidores embebidos. */
    public DownloadJobListener(DownloadJobHandler handler, DownloadWorkerHeartbeat heartbeat) {
        this(handler, heartbeat, null, null);
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
        try {
            handler.handle(event);
            heartbeat.success();
        } catch (CapacityDeferredException exception) {
            if (rabbitTemplate == null || messaging == null) {
                throw exception;
            }
            rabbitTemplate.convertAndSend("", messaging.capacityWaitQueue(), event);
            heartbeat.success();
        } catch (RuntimeException exception) {
            heartbeat.failure(exception);
            throw exception;
        }
    }
}
