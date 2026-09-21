package es.ubu.batchdownloader.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Define el enrutamiento durable de solicitudes de correo y de entregas descartadas.
 *
 * @param exchange Exchange AMQP interno al que se suscribe el servicio.
 * @param routingKey Clave de enrutamiento recibida de RabbitMQ.
 * @param queue Cola durable de solicitudes de correo.
 * @param deadLetterExchange Exchange que recibe entregas rechazadas definitivamente.
 * @param deadLetterRoutingKey Clave usada al enviar una entrega a la cola de descartes.
 * @param deadLetterQueue Cola durable que conserva entregas rechazadas.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.messaging.RabbitTopologyConfiguration
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.NotificationRequestedMessageMapper
 *
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@ConfigurationProperties(prefix = "notification.rabbit")
public record RabbitTopologyProperties(
        String exchange,
        String routingKey,
        String queue,
        String deadLetterExchange,
        String deadLetterRoutingKey,
        String deadLetterQueue) {

    /**
     * Exige nombres no vacíos para exchanges, colas y claves y elimina espacios exteriores.
     *
     * @param exchange Exchange AMQP interno al que se suscribe el servicio.
     * @param routingKey Clave de enrutamiento recibida de RabbitMQ.
     * @param queue Cola durable de solicitudes de correo.
     * @param deadLetterExchange Exchange que recibe entregas rechazadas definitivamente.
     * @param deadLetterRoutingKey Clave usada al enviar una entrega a la cola de descartes.
     * @param deadLetterQueue Cola durable que conserva entregas rechazadas.
     * @throws IllegalArgumentException si falta cualquiera de los nombres de la topología.
     */
    public RabbitTopologyProperties {
        exchange = requireText(exchange, "exchange");
        routingKey = requireText(routingKey, "routing-key");
        queue = requireText(queue, "queue");
        deadLetterExchange = requireText(deadLetterExchange, "dead-letter-exchange");
        deadLetterRoutingKey = requireText(deadLetterRoutingKey, "dead-letter-routing-key");
        deadLetterQueue = requireText(deadLetterQueue, "dead-letter-queue");
    }

    /**
     * Valida un nombre obligatorio de la topología e identifica su propiedad si falta.
     *
     * @param value Contenido recibido antes de aplicar la validación indicada.
     * @param property Sufijo de la propiedad notification.rabbit que se identifica en el error.
     * @return nombre sin espacios exteriores.
     * @throws IllegalArgumentException si el nombre es null o está en blanco.
     */
    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("notification.rabbit." + property + " no puede estar vacío");
        }
        return value.strip();
    }
}
