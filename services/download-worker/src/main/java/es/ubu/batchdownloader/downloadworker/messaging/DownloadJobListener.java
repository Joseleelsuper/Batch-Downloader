package es.ubu.batchdownloader.downloadworker.messaging;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler;
import es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.operations.DownloadWorkerHeartbeat;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Ejecuta comandos ya admitidos por Core en hilos virtuales y confirma AMQP después de completarlos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobHandler
 * @see es.ubu.batchdownloader.downloadworker.operations.DownloadWorkerHeartbeat
 * @see es.ubu.batchdownloader.downloadworker.messaging.DownloadJobFailureRecoverer
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y operación del worker
 */
@Component
public class DownloadJobListener {
    private final DownloadJobHandler handler;
    private final DownloadWorkerHeartbeat heartbeat;

    /**
     * Configura procesamiento y salud del consumidor.
     *
     * @param handler Cadena que valida, deduplica y procesa la solicitud.
     * @param heartbeat Señal de salud que distingue una ejecución atendida de un fallo del
     *     consumidor.
     */
    public DownloadJobListener(
            @Qualifier("downloadJobHandler") DownloadJobHandler handler,
            DownloadWorkerHeartbeat heartbeat) {
        this.handler = handler;
        this.heartbeat = heartbeat;
    }

    /**
     * Mantiene el ACK pendiente sin ocupar un consumidor hasta confirmar el procesamiento duradero.
     *
     * @param event Sobre recibido con identidad y carga del trabajo solicitado o cancelado.
     */
    @RabbitListener(
            queues = "${download-worker.messaging.input-queue}",
            containerFactory = "downloadRabbitListenerContainerFactory")
    public java.util.concurrent.CompletableFuture<Void> receiveAsync(DownloadJobRequestedEvent event) {
        var completed = new java.util.concurrent.CompletableFuture<Void>();
        Thread.ofVirtual().name("download-job").start(() -> {
            try {
                receive(event);
                completed.complete(null);
            } catch (RuntimeException exception) {
                if (!(exception instanceof org.springframework.amqp.AmqpRejectAndDontRequeueException)) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                completed.completeExceptionally(exception);
            }
        });
        return completed;
    }

    public void receive(DownloadJobRequestedEvent event) {
        try {
            handler.handle(event);
            heartbeat.success();
        } catch (CapacityDeferredException exception) {
            // La cola FIFO de Core es la única autoridad de admisión por bytes.
            throw exception;
        } catch (RuntimeException exception) {
            heartbeat.failure(exception);
            throw exception;
        }
    }
}
