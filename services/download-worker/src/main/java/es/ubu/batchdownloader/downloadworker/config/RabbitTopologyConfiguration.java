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

/**
 * Define la configuración utilizada por {@code RabbitTopologyConfiguration}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Configuration
public class RabbitTopologyConfiguration {
    /**
     * Ejecuta la operación {@code downloadCommandsExchange}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadCommandsExchange}.
     */
    @Bean
    TopicExchange downloadCommandsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.commandExchange(), true, false);
    }

    /**
     * Ejecuta la operación {@code downloadEventsExchange}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadEventsExchange}.
     */
    @Bean
    TopicExchange downloadEventsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.eventExchange(), true, false);
    }

    /**
     * Ejecuta la operación {@code downloadDeadLetterExchange}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadDeadLetterExchange}.
     */
    @Bean
    DirectExchange downloadDeadLetterExchange(MessagingProperties properties) {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    /**
     * Ejecuta la operación {@code downloadJobQueue}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadJobQueue}.
     */
    @Bean
    Queue downloadJobQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.inputQueue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .build();
    }

    /**
     * Ejecuta la operación {@code downloadCancellationQueue}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadCancellationQueue}.
     */
    @Bean
    Queue downloadCancellationQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.cancellationQueue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .build();
    }

    /**
     * Ejecuta la operación {@code downloadJobDeadLetterQueue}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadJobDeadLetterQueue}.
     */
    @Bean
    Queue downloadJobDeadLetterQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    /**
     * Ejecuta la operación {@code downloadJobBinding}.
     *
     * @param downloadJobQueue Valor de {@code downloadJobQueue} utilizado por la operación.
     * @param downloadCommandsExchange Valor de {@code downloadCommandsExchange} utilizado por la
     *     operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadJobBinding}.
     */
    @Bean
    Binding downloadJobBinding(
            Queue downloadJobQueue,
            TopicExchange downloadCommandsExchange,
            MessagingProperties properties) {
        return BindingBuilder.bind(downloadJobQueue)
                .to(downloadCommandsExchange)
                .with(properties.inputRoutingKey());
    }

    /**
     * Ejecuta la operación {@code downloadCancellationBinding}.
     *
     * @param downloadCancellationQueue Valor de {@code downloadCancellationQueue} utilizado por la
     *     operación.
     * @param downloadCommandsExchange Valor de {@code downloadCommandsExchange} utilizado por la
     *     operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadCancellationBinding}.
     */
    @Bean
    Binding downloadCancellationBinding(
            Queue downloadCancellationQueue,
            TopicExchange downloadCommandsExchange,
            MessagingProperties properties) {
        return BindingBuilder.bind(downloadCancellationQueue)
                .to(downloadCommandsExchange)
                .with(properties.cancellationRoutingKey());
    }

    /**
     * Ejecuta la operación {@code downloadDeadLetterBinding}.
     *
     * @param downloadJobDeadLetterQueue Valor de {@code downloadJobDeadLetterQueue} utilizado por
     *     la operación.
     * @param downloadDeadLetterExchange Valor de {@code downloadDeadLetterExchange} utilizado por
     *     la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @return Resultado producido por {@code downloadDeadLetterBinding}.
     */
    @Bean
    Binding downloadDeadLetterBinding(
            Queue downloadJobDeadLetterQueue,
            DirectExchange downloadDeadLetterExchange,
            MessagingProperties properties) {
        return BindingBuilder.bind(downloadJobDeadLetterQueue)
                .to(downloadDeadLetterExchange)
                .with(properties.deadLetterQueue());
    }

    /**
     * Ejecuta la operación {@code rabbitMessageConverter}.
     *
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @return Resultado producido por {@code rabbitMessageConverter}.
     */
    @Bean
    MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * Ejecuta la operación {@code downloadRetryInterceptor}.
     *
     * @param properties Valor de {@code properties} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param eventPublisher Valor de {@code eventPublisher} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     * @return Resultado producido por {@code downloadRetryInterceptor}.
     */
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

    /**
     * Ejecuta la operación {@code downloadRabbitListenerContainerFactory}.
     *
     * @param connectionFactory Valor de {@code connectionFactory} utilizado por la operación.
     * @param rabbitMessageConverter Valor de {@code rabbitMessageConverter} utilizado por la
     *     operación.
     * @param downloadRetryInterceptor Valor de {@code downloadRetryInterceptor} utilizado por la
     *     operación.
     * @return Resultado producido por {@code downloadRabbitListenerContainerFactory}.
     */
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
