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
 * Conecta RabbitMQ con la cadena de procesamiento y la salud del worker, desviando falta de
 * capacidad a una cola con espera en lugar de convertirla en resultado terminal.
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
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties messaging;

    /**
     * Configura procesamiento, salud y publicación a la cola de espera por capacidad.
     *
     * @param handler Cadena que valida, deduplica y procesa la solicitud.
     * @param heartbeat Señal de salud que distingue una ejecución atendida de un fallo del
     *     consumidor.
     * @param rabbitTemplate Publicador que mueve solicitudes sin capacidad a la cola de espera.
     * @param messaging Nombres y demora de las colas del worker.
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

    /**
     * Compone un listener aislado que propaga los aplazamientos al no disponer de la cola de
     * espera.
     *
     * @param handler Cadena que valida, deduplica y procesa la solicitud.
     * @param heartbeat Señal de salud que distingue una ejecución atendida de un fallo del
     *     consumidor.
     */
    public DownloadJobListener(DownloadJobHandler handler, DownloadWorkerHeartbeat heartbeat) {
        this(handler, heartbeat, null, null);
    }

    /**
     * Procesa el comando y registra éxito; si falta capacidad lo mueve a la cola de espera cuando
     * está configurada. Otros fallos se registran en salud y se propagan al interceptor.
     *
     * @param event Sobre recibido con identidad y carga del trabajo solicitado o cancelado.
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
