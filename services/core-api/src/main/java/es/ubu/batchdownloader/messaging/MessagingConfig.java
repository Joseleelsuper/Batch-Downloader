package es.ubu.batchdownloader.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
class MessagingConfig {
    @Bean
    TopicExchange batchDownloaderExchange(@Value("${app.messaging.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    TopicExchange batchDownloaderEventsExchange(
            @Value("${app.messaging.events-exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Queue coreDownloadEventsQueue(@Value("${app.messaging.download-events-queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    Binding coreDownloadEventsBinding(
            Queue coreDownloadEventsQueue,
            TopicExchange batchDownloaderEventsExchange) {
        return BindingBuilder.bind(coreDownloadEventsQueue)
                .to(batchDownloaderEventsExchange)
                .with("download.job.#");
    }
}
