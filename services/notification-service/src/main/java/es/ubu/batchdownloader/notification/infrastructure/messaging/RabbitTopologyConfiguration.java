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
 * Declara exchanges, colas durables y enlaces de solicitudes de correo y entregas rechazadas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.config.RabbitTopologyProperties
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.RabbitNotificationRequestedListener
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Configuration
@EnableRabbit
public class RabbitTopologyConfiguration {

    /**
     * Declara el exchange topic durable y sin borrado automático de los eventos del productor.
     *
     * @param properties Nombres configurados de exchanges, colas y claves de enrutamiento.
     * @return exchange compartido al que se enlaza la cola de notificaciones.
     */
    @Bean
    TopicExchange downloadEventsExchange(RabbitTopologyProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    /**
     * Declara el exchange directo durable que recibe entregas rechazadas por el consumidor.
     *
     * @param properties Nombres configurados de exchanges, colas y claves de enrutamiento.
     * @return exchange de descartes sin borrado automático.
     */
    @Bean
    DirectExchange notificationDeadLetterExchange(RabbitTopologyProperties properties) {
        return new DirectExchange(properties.deadLetterExchange(), true, false);
    }

    /**
     * Declara la cola durable de entrada con su exchange y clave de dead letter configurados.
     *
     * @param properties Nombres configurados de exchanges, colas y claves de enrutamiento.
     * @return cola que redirige entregas rechazadas a la topología de descartes.
     */
    @Bean
    Queue notificationQueue(RabbitTopologyProperties properties) {
        return QueueBuilder.durable(properties.queue())
                .deadLetterExchange(properties.deadLetterExchange())
                .deadLetterRoutingKey(properties.deadLetterRoutingKey())
                .build();
    }

    /**
     * Declara la cola durable que conserva solicitudes rechazadas para su diagnóstico.
     *
     * @param properties Nombres configurados de exchanges, colas y claves de enrutamiento.
     * @return cola de descartes configurada.
     */
    @Bean
    Queue notificationDeadLetterQueue(RabbitTopologyProperties properties) {
        return QueueBuilder.durable(properties.deadLetterQueue()).build();
    }

    /**
     * Suscribe la cola de correo al exchange de eventos mediante la clave aceptada por el
     * conversor.
     *
     * @param notificationQueue Cola durable de entrada que recibe solicitudes de correo.
     * @param downloadEventsExchange Exchange topic compartido de eventos del productor.
     * @param properties Nombres configurados de exchanges, colas y claves de enrutamiento.
     * @return enlace de entrada del consumidor.
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
     * Conecta el exchange de descartes con su cola mediante la clave de rechazo configurada.
     *
     * @param notificationDeadLetterQueue Cola que conserva las entregas rechazadas.
     * @param notificationDeadLetterExchange Exchange directo que enruta las entregas rechazadas.
     * @param properties Nombres configurados de exchanges, colas y claves de enrutamiento.
     * @return enlace para conservar entregas rechazadas.
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
