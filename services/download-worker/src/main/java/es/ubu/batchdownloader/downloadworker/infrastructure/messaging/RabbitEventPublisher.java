package es.ubu.batchdownloader.downloadworker.infrastructure.messaging;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.MessagingProperties;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public class RabbitEventPublisher implements EventPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public RabbitEventPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(String routingKey, Object event) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(properties.eventExchange(), routingKey, event, correlation);
        try {
            CorrelationData.Confirm confirmation = correlation.getFuture().get(10, TimeUnit.SECONDS);
            if (!confirmation.isAck()) {
                throw new InfrastructureException(
                        "rabbit_publish_rejected", new IllegalStateException(confirmation.getReason()));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("rabbit_publish_interrupted", exception);
        } catch (InfrastructureException exception) {
            throw exception;
        } catch (ExecutionException | TimeoutException exception) {
            throw new InfrastructureException("rabbit_publish_confirmation_failed", exception);
        }
    }
}
