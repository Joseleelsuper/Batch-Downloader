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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * Conecta colas duraderas de descarga, cancelación, rechazo y espera por capacidad con consumidores
 * acotados y política de recuperación terminal.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.config.MessagingProperties
 * @see es.ubu.batchdownloader.downloadworker.messaging.DownloadJobFailureRecoverer
 * @see es.ubu.batchdownloader.downloadworker.messaging.DownloadJobListener
 * @since 0.1.0
 * @version 0.1.0
 * @category Configuración del worker
 */
@Configuration
public class RabbitTopologyConfiguration {
    /**
     * Declara el exchange topic duradero de comandos sin eliminación automática.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return exchange configurado para solicitudes y cancelaciones.
     */
    @Bean
    TopicExchange downloadCommandsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.commandExchange(), true, false);
    }

    /**
     * Declara el exchange topic duradero de resultados del procesamiento.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return exchange de eventos destinados a Core.
     */
    @Bean
    TopicExchange downloadEventsExchange(MessagingProperties properties) {
        return new TopicExchange(properties.eventExchange(), true, false);
    }

    /**
     * Declara el exchange directo duradero al que se desvían comandos rechazados.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return exchange de mensajes descartados por su política de entrega.
     */
    @Bean
    DirectExchange downloadDeadLetterExchange(MessagingProperties properties) {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    /**
     * Declara solicitudes duraderas y su destino de rechazo tras agotar el procesamiento.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return cola de entrada con enrutamiento dead-letter.
     */
    @Bean
    Queue downloadJobQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.inputQueue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .build();
    }

    /**
     * Declara cancelaciones duraderas en una cola independiente con el mismo destino de rechazo.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return cola que puede consumirse aunque estén ocupados los trabajos de descarga.
     */
    @Bean
    Queue downloadCancellationQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.cancellationQueue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterQueue())
                .build();
    }

    /**
     * Declara la cola duradera que conserva comandos rechazados.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return cola de diagnóstico sin borrado automático.
     */
    @Bean
    Queue downloadJobDeadLetterQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    /**
     * Declara una cola con TTL que devuelve los mensajes al exchange y clave de solicitudes al
     * terminar la espera.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return cola de aplazamiento por capacidad.
     * @throws ArithmeticException si el TTL en milisegundos no cabe en el entero requerido por
     *     RabbitMQ.
     */
    @Bean
    Queue downloadCapacityWaitQueue(MessagingProperties properties) {
        return QueueBuilder.durable(properties.capacityWaitQueue())
                .ttl(Math.toIntExact(properties.capacityWaitDelay().toMillis()))
                .deadLetterExchange(properties.commandExchange())
                .deadLetterRoutingKey(properties.inputRoutingKey())
                .build();
    }

    /**
     * Suscribe la cola de descargas a la clave configurada de solicitudes.
     *
     * @param downloadJobQueue Cola de solicitudes de descarga que se vincula al exchange de
     *     comandos.
     * @param downloadCommandsExchange Exchange donde Core publica solicitudes y cancelaciones.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return vinculación topic de comandos de descarga.
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
     * Suscribe la cola independiente a la clave de solicitudes de cancelación.
     *
     * @param downloadCancellationQueue Cola independiente que recibe cancelaciones.
     * @param downloadCommandsExchange Exchange donde Core publica solicitudes y cancelaciones.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return vinculación topic de cancelaciones.
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
     * Enruta mensajes rechazados hacia la cola de diagnóstico mediante su nombre como clave
     * directa.
     *
     * @param downloadJobDeadLetterQueue Cola duradera que conserva los mensajes rechazados.
     * @param downloadDeadLetterExchange Exchange directo que enruta hacia la cola de rechazo.
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @return vinculación del exchange dead-letter.
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
     * Reutiliza el ObjectMapper configurado al convertir sobres de evento hacia y desde mensajes
     * AMQP.
     *
     * @param objectMapper Serializador JSON configurado para los contratos de eventos o peticiones
     *     internas.
     * @return conversor JSON para RabbitMQ.
     */
    @Bean
    MessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    /**
     * Configura intentos sin estado y espera exponencial acotada, con recuperación que publica un
     * fallo terminal antes de rechazar el comando.
     *
     * @param properties Configuración tipada de destinos, límites o credenciales que utiliza el
     *     componente construido.
     * @param objectMapper Serializador JSON configurado para los contratos de eventos o peticiones
     *     internas.
     * @param eventPublisher Publicación de resultados terminales cuando se agotan reintentos del
     *     comando.
     * @param clock Reloj UTC compartido para eventos, reservas y métricas.
     * @return interceptor de reintentos del consumidor.
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
     * Recibe asincrónicamente todos los trabajos ya admitidos por el ledger de Core.
     *
     * @param connectionFactory Conexiones AMQP del proceso utilizadas por los contenedores de
     *     consumidores.
     * @param rabbitMessageConverter Conversor JSON común de los sobres de eventos.
     * @return factoría de consumidores de trabajos con conversión JSON y reintentos.
     */
    @Bean(name = "downloadRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory downloadRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter rabbitMessageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        // CompletableFuture mantiene el ACK pendiente sin ocupar un consumidor por trabajo.
        // La admisión duradera por bytes de Core limita la carga, no un máximo de trabajos.
        factory.setDefaultRequeueRejected(true);
        factory.setPrefetchCount(0);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        return factory;
    }

    /**
     * Crea consumidores independientes para cancelación con prefetch uno, concurrencia fija y la
     * política de reintentos compartida.
     *
     * @param connectionFactory Conexiones AMQP del proceso utilizadas por los contenedores de
     *     consumidores.
     * @param rabbitMessageConverter Conversor JSON común de los sobres de eventos.
     * @param downloadRetryInterceptor Política de intentos y recuperación terminal aplicada al
     *     consumidor.
     * @param concurrency Número de consumidores de cancelación, independiente de los trabajos de
     *     descarga.
     * @return factoría que permite procesar cancelaciones durante trabajos activos.
     */
    @Bean(name = "downloadCancellationRabbitListenerContainerFactory")
    SimpleRabbitListenerContainerFactory downloadCancellationRabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            MessageConverter rabbitMessageConverter,
            RetryOperationsInterceptor downloadRetryInterceptor,
            @Value("${download-worker.messaging.cancellation-concurrency:2}") int concurrency) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(rabbitMessageConverter);
        factory.setAdviceChain(downloadRetryInterceptor);
        factory.setDefaultRequeueRejected(false);
        factory.setPrefetchCount(1);
        factory.setConcurrentConsumers(concurrency);
        factory.setMaxConcurrentConsumers(concurrency);
        return factory;
    }
}
