package es.ubu.batchdownloader.downloadworker.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.downloadworker.config.MessagingProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

class RabbitQueueMetricsTest {

    @Test
    void refreshPublishesQueueDepthAndConsumerSnapshot() {
        AmqpAdmin rabbit = mock(AmqpAdmin.class);
        MessagingProperties messaging = mock(MessagingProperties.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Properties input = new Properties();
        input.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 7);
        input.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, 3L);
        Properties waiting = new Properties();
        waiting.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, 2);

        when(messaging.inputQueue()).thenReturn("input");
        when(messaging.capacityWaitQueue()).thenReturn("capacity-wait");
        when(rabbit.getQueueProperties("input")).thenReturn(input);
        when(rabbit.getQueueProperties("capacity-wait")).thenReturn(waiting);

        RabbitQueueMetrics metrics = new RabbitQueueMetrics(rabbit, messaging, registry);
        metrics.refresh();

        assertThat(registry.get("download_worker_queue_depth").gauge().value()).isEqualTo(9);
        assertThat(registry.get("download_worker_capacity_wait_queue_depth").gauge().value())
                .isEqualTo(2);
        assertThat(registry.get("download_worker_queue_consumers").gauge().value()).isEqualTo(3);
    }

    @Test
    void refreshKeepsPreviousSnapshotWhenRabbitDoesNotReturnQueueProperties() {
        AmqpAdmin rabbit = mock(AmqpAdmin.class);
        MessagingProperties messaging = mock(MessagingProperties.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Properties input = new Properties();
        input.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, "unknown");
        input.put(RabbitAdmin.QUEUE_CONSUMER_COUNT, "unknown");
        Properties waiting = new Properties();
        waiting.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, "unknown");

        when(messaging.inputQueue()).thenReturn("input");
        when(messaging.capacityWaitQueue()).thenReturn("capacity-wait");
        when(rabbit.getQueueProperties("input"))
                .thenReturn(input)
                .thenReturn((Properties) null);
        when(rabbit.getQueueProperties("capacity-wait"))
                .thenReturn(waiting)
                .thenReturn((Properties) null);

        RabbitQueueMetrics metrics = new RabbitQueueMetrics(rabbit, messaging, registry);
        metrics.refresh();
        metrics.refresh();

        assertThat(registry.get("download_worker_queue_depth").gauge().value()).isZero();
        assertThat(registry.get("download_worker_capacity_wait_queue_depth").gauge().value())
                .isZero();
        assertThat(registry.get("download_worker_queue_consumers").gauge().value()).isZero();
    }
}
