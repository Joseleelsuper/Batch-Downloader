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
 * Publica resultados del worker en el exchange de eventos y exige acuse correlacionado del broker
 * antes de considerar terminada la entrega.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.EventPublisher
 * @since 0.1.0
 * @version 0.1.0
 * @category Adaptadores y persistencia del worker
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
     * Conecta RabbitTemplate con el exchange de eventos configurado.
     *
     * @param rabbitTemplate Publicador AMQP configurado con confirmación correlacionada.
     * @param properties Configuración específica del adaptador: destino, credencial y límites de
     *     acceso.
     */
    public RabbitEventPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    /**
     * Envía el sobre y espera hasta diez segundos por un acuse positivo, conservando la
     * interrupción si se cancela la espera.
     *
     * @param routingKey Clave de evento que selecciona los consumidores de Core.
     * @param event Sobre del contrato de eventos de descarga que se publica.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si el
     *     broker rechaza la publicación, falla la confirmación o se interrumpe la espera.
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
