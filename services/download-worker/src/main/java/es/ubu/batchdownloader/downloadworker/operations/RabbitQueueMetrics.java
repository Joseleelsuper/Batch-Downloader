package es.ubu.batchdownloader.downloadworker.operations;

import es.ubu.batchdownloader.downloadworker.config.MessagingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Muestrea profundidad y consumidores de la cola sin consultar RabbitMQ desde el scrape. */
@Component
final class RabbitQueueMetrics {
    private final AmqpAdmin rabbit;
    private final MessagingProperties messaging;
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicInteger capacityWaiting = new AtomicInteger();
    private final AtomicInteger consumers = new AtomicInteger();

    /** Inicializa los medidores cacheados. */
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

    /** Actualiza el snapshot cada diez segundos; un fallo conserva el último valor conocido. */
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

    private double totalQueued() {
        return queued.get() + capacityWaiting.get();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
