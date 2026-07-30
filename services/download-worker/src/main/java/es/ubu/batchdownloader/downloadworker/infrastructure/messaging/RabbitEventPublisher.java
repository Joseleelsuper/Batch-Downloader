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

/**
 * Publica los datos gestionados por {@code RabbitEventPublisher}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class RabbitEventPublisher implements EventPublisher {
    /**
     * Estado {@code rabbitTemplate} mantenido por {@code RabbitEventPublisher}.
     */
    private final RabbitTemplate rabbitTemplate;
    /**
     * Estado {@code properties} mantenido por {@code RabbitEventPublisher}.
     */
    private final MessagingProperties properties;

    /**
     * Inicializa una instancia de {@code RabbitEventPublisher}.
     *
     * @param rabbitTemplate Valor de {@code rabbitTemplate} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public RabbitEventPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * Publica el contenido solicitado mediante {@code publish}.
     *
     * @param routingKey Valor de {@code routingKey} utilizado por la operación.
     * @param event Evento que debe procesarse.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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
