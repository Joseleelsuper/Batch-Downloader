package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.operations.DownloadWorkerHeartbeat;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
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

    /**
     * Inicializa una instancia de {@code DownloadJobListener}.
     *
     * @param handler cadena de políticas y caso de uso que procesa el evento.
     */
    @Autowired
    public DownloadJobListener(
            @Qualifier("downloadJobHandler") DownloadJobHandler handler,
            DownloadWorkerHeartbeat heartbeat) {
        this.handler = handler;
        this.heartbeat = heartbeat;
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
        } catch (RuntimeException exception) {
            heartbeat.failure(exception);
            throw exception;
        }
    }
}
