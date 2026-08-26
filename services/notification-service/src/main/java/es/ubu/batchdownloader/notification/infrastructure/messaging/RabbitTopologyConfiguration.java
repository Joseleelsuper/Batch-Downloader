package es.ubu.batchdownloader.notification.infrastructure.messaging;

import es.ubu.batchdownloader.notification.config.RabbitTopologyProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Define la configuración utilizada por {@code RabbitTopologyConfiguration}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Configuration
@EnableRabbit
public class RabbitTopologyConfiguration {

    /**
     * Ejecuta la operación {@code downloadEventsExchange}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadEventsExchange}.
     */
    @Bean
    TopicExchange downloadEventsExchange(RabbitTopologyProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    /**
     * Ejecuta la operación {@code notificationDeadLetterExchange}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code notificationDeadLetterExchange}.
     */
    @Bean
    DirectExchange notificationDeadLetterExchange(RabbitTopologyProperties properties) {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    /**
     * Ejecuta la operación {@code notificationQueue}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code notificationQueue}.
     */
    @Bean
    Queue notificationQueue(RabbitTopologyProperties properties) {
        return QueueBuilder.durable(properties.queue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterRoutingKey())
                .build();
    }

    /**
     * Ejecuta la operación {@code notificationDeadLetterQueue}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code notificationDeadLetterQueue}.
     */
    @Bean
    Queue notificationDeadLetterQueue(RabbitTopologyProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    /**
     * Ejecuta la operación {@code notificationRequestedBinding}.
     *
     * @param notificationQueue Valor de {@code notificationQueue} utilizado por la operación.
     * @param downloadEventsExchange Valor de {@code downloadEventsExchange} utilizado por la
     *     operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code notificationRequestedBinding}.
     */
    @Bean
    Binding notificationRequestedBinding(
            Queue notificationQueue,
            TopicExchange downloadEventsExchange,
            RabbitTopologyProperties properties) {
        return BindingBuilder.bind(notificationQueue)
                .to(downloadEventsExchange)
                .with(properties.routingKey());
    }

    /**
     * Ejecuta la operación {@code deadLetterBinding}.
     *
     * @param notificationDeadLetterQueue Valor de {@code notificationDeadLetterQueue} utilizado por
     *     la operación.
     * @param notificationDeadLetterExchange Valor de {@code notificationDeadLetterExchange}
     *     utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code deadLetterBinding}.
     */
    @Bean
    Binding deadLetterBinding(
            Queue notificationDeadLetterQueue,
            DirectExchange notificationDeadLetterExchange,
            RabbitTopologyProperties properties) {
        return BindingBuilder.bind(notificationDeadLetterQueue)
                .to(notificationDeadLetterExchange)
                .with(properties.deadLetterRoutingKey());
    }
}
