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

@Configuration
@EnableRabbit
public class RabbitTopologyConfiguration {

    @Bean
    TopicExchange downloadEventsExchange(RabbitTopologyProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    DirectExchange notificationDeadLetterExchange(RabbitTopologyProperties properties) {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    @Bean
    Queue notificationQueue(RabbitTopologyProperties properties) {
        return QueueBuilder.durable(properties.queue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterRoutingKey())
                .build();
    }

    @Bean
    Queue notificationDeadLetterQueue(RabbitTopologyProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    @Bean
    Binding notificationRequestedBinding(
            Queue notificationQueue,
            TopicExchange downloadEventsExchange,
            RabbitTopologyProperties properties) {
        return BindingBuilder.bind(notificationQueue)
                .to(downloadEventsExchange)
                .with(properties.routingKey());
    }

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
