package es.ubu.batchdownloader.downloadworker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.messaging.DownloadJobFailureRecoverer;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import java.time.Clock;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

@Configuration
public class RabbitTopologyConfiguration {
    @Bean
    TopicExchange downloadCommandsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.commandExchange(), true, false);
    }

    @Bean
    TopicExchange downloadEventsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.eventExchange(), true, false);
    }

    @Bean
    DirectExchange downloadDeadLetterExchange(MessagingProperties properties) {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    @Bean
    Queue downloadJobQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.inputQueue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .build();
    }

    @Bean
    Queue downloadCancellationQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.cancellationQueue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .build();
    }

    @Bean
    Queue downloadJobDeadLetterQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    @Bean
    Binding downloadJobBinding(
            Queue downloadJobQueue,
            TopicExchange downloadCommandsExchange,
            MessagingProperties properties) {
        return BindingBuilder.bind(downloadJobQueue)
                .to(downloadCommandsExchange)
                .with(properties.inputRoutingKey());
    }

    @Bean
    Binding downloadCancellationBinding(
            Queue downloadCancellationQueue,
            TopicExchange downloadCommandsExchange,
            MessagingProperties properties) {
        return BindingBuilder.bind(downloadCancellationQueue)
                .to(downloadCommandsExchange)
                .with(properties.cancellationRoutingKey());
    }

    @Bean
    Binding downloadDeadLetterBinding(
            Queue downloadJobDeadLetterQueue,
            DirectExchange downloadDeadLetterExchange,
            MessagingProperties properties) {
        return BindingBuilder.bind(downloadJobDeadLetterQueue)
                .to(downloadDeadLetterExchange)
                .with(properties.deadLetterQueue());
    }

    @Bean
    MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    RetryOperationsInterceptor downloadRetryInterceptor(
            MessagingProperties properties,
            ObjectMapper objectMapper,
            EventPublisher eventPublisher,
            Clock clock) {
        return RetryInterceptorBuilder.stateless()
                .maxAttempts(properties.retryAttempts())
                .backOffOptions(
                        properties.retryInitialInterval().toMillis(),
                        properties.retryMultiplier(),
                        properties.retryMaxInterval().toMillis())
                .recoverer(new DownloadJobFailureRecoverer(objectMapper, eventPublisher, clock))
                .build();
    }

    @Bean(name = "downloadRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory downloadRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter rabbitMessageConverter,
            RetryOperationsInterceptor downloadRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        factory.setAdviceChain(downloadRetryInterceptor);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        return factory;
    }
}
