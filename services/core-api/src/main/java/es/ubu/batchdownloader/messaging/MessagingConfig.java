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

/**
 * Define la configuración utilizada por {@code MessagingConfig}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Configuration
@EnableScheduling
class MessagingConfig {
    /**
     * Ejecuta la operación {@code batchDownloaderExchange}.
     *
     * @param exchange Valor de {@code exchange} utilizado por la operación.
     * @return Resultado producido por {@code batchDownloaderExchange}.
     */
    @Bean
    TopicExchange batchDownloaderExchange(@Value("${app.messaging.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    /**
     * Ejecuta la operación {@code batchDownloaderEventsExchange}.
     *
     * @param exchange Valor de {@code exchange} utilizado por la operación.
     * @return Resultado producido por {@code batchDownloaderEventsExchange}.
     */
    @Bean
    TopicExchange batchDownloaderEventsExchange(
            @Value("${app.messaging.events-exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    /**
     * Ejecuta la operación {@code coreDownloadEventsQueue}.
     *
     * @param queue Valor de {@code queue} utilizado por la operación.
     * @return Resultado producido por {@code coreDownloadEventsQueue}.
     */
    @Bean
    Queue coreDownloadEventsQueue(@Value("${app.messaging.download-events-queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    /**
     * Ejecuta la operación {@code coreDownloadEventsBinding}.
     *
     * @param coreDownloadEventsQueue Valor de {@code coreDownloadEventsQueue} utilizado por la
     *     operación.
     * @param batchDownloaderEventsExchange Valor de {@code batchDownloaderEventsExchange} utilizado
     *     por la operación.
     * @return Resultado producido por {@code coreDownloadEventsBinding}.
     */
    @Bean
    Binding coreDownloadEventsBinding(
            Queue coreDownloadEventsQueue,
            TopicExchange batchDownloaderEventsExchange) {
        return BindingBuilder.bind(coreDownloadEventsQueue)
                .to(batchDownloaderEventsExchange)
                .with("download.job.#");
    }
}
