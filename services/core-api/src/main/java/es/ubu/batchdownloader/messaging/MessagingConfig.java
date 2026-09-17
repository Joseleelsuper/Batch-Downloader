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
 * Declara exchanges y cola duraderos de Core y vincula los eventos download.job.# para que el
 * consumidor actualice los trabajos de descarga.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.messaging.OutboxDispatcher
 * @see es.ubu.batchdownloader.downloads.infrastructure.messaging.DownloadWorkerEventListener
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y retención
 */
@Configuration
@EnableScheduling
class MessagingConfig {
    /**
     * Declara el exchange topic duradero donde Core publica solicitudes del outbox.
     *
     * @param exchange Nombre configurado del exchange duradero de publicación.
     * @return exchange que no se elimina automáticamente al desconectarse los clientes.
     */
    @Bean
    TopicExchange batchDownloaderExchange(@Value("${app.messaging.exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    /**
     * Declara el exchange topic duradero por el que llegan eventos de procesamiento.
     *
     * @param exchange Nombre configurado del exchange duradero de publicación.
     * @return exchange de eventos sin eliminación automática.
     */
    @Bean
    TopicExchange batchDownloaderEventsExchange(
            @Value("${app.messaging.events-exchange}") String exchange) {
        return new TopicExchange(exchange, true, false);
    }

    /**
     * Declara la cola duradera que conserva los eventos de descarga destinados a Core.
     *
     * @param queue Nombre configurado de la cola duradera de eventos de descarga de Core.
     * @return cola de entrada persistente.
     */
    @Bean
    Queue coreDownloadEventsQueue(@Value("${app.messaging.download-events-queue}") String queue) {
        return QueueBuilder.durable(queue).build();
    }

    /**
     * Suscribe la cola de Core a todos los eventos bajo download.job del exchange de eventos.
     *
     * @param coreDownloadEventsQueue Cola que recibe los cambios de estado de descargas producidos
     *     por el worker.
     * @param batchDownloaderEventsExchange Exchange de eventos al que se vincula la cola de Core.
     * @return vinculación topic con patrón download.job.#.
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
