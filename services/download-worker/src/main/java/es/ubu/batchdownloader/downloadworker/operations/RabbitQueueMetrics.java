package es.ubu.batchdownloader.downloadworker.operations;

import es.ubu.batchdownloader.downloadworker.config.MessagingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consulta periódicamente mensajes pendientes y consumidores del worker e incluye la cola de espera
 * por capacidad en la profundidad total.
 *
 * @see es.ubu.batchdownloader.downloadworker.config.MessagingProperties
 * @see es.ubu.batchdownloader.downloadworker.messaging.DownloadJobListener
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y operación del worker
 */
@Component
final class RabbitQueueMetrics {
    private final AmqpAdmin rabbit;
    private final MessagingProperties messaging;
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger capacityWaiting = new AtomicInteger();
    private final AtomicInteger consumers = new AtomicInteger();

    /**
     * Conecta consulta AMQP y nombres de colas y registra profundidad y consumidores.
     *
     * @param rabbit Consulta administrativa de profundidad y consumidores de las colas.
     * @param messaging Nombres y demora de las colas del worker.
     * @param registry Registro de métricas de espera y número de consumidores.
     */
    RabbitQueueMetrics(
            AmqpAdmin rabbit,
            MessagingProperties messaging,
            MeterRegistry registry) {
        this.rabbit = rabbit;
        this.messaging = messaging;
        registry.gauge("download_worker_queue_depth", this, RabbitQueueMetrics::totalQueued);
        registry.gauge("download_worker_capacity_wait_queue_depth", capacityWaiting);
        registry.gauge("download_worker_queue_consumers", consumers);
    }

    /**
     * Actualiza profundidad de solicitudes, consumidores y mensajes esperando capacidad; conserva
     * el valor anterior cuando una cola no devuelve propiedades.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void refresh() {
        Properties properties = rabbit.getQueueProperties(messaging.inputQueue());
        if (properties != null) {
            queued.set(number(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)));
            consumers.set(number(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)));
        }
        Properties waiting = rabbit.getQueueProperties(messaging.capacityWaitQueue());
        if (waiting != null) {
            capacityWaiting.set(number(waiting.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)));
        }
    }

    /**
     * Suma las solicitudes pendientes y los trabajos aplazados por capacidad.
     *
     * @return cantidad total de mensajes esperando procesamiento.
     */
    private double totalQueued() {
        return queued.get() + capacityWaiting.get();
    }

    /**
     * Convierte contadores numéricos proporcionados por RabbitMQ y representa otros valores como
     * cero.
     *
     * @param value Propiedad de cola que se acepta como contador únicamente cuando es numérica.
     * @return valor entero de la propiedad o cero.
     */
    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
