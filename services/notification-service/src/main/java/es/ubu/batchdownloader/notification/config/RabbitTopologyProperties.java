package es.ubu.batchdownloader.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Representa los datos inmutables de {@code RabbitTopologyProperties}.
 *
 * @param exchange Valor de {@code exchange} incluido en el record.
 * @param routingKey Valor de {@code routingKey} incluido en el record.
 * @param queue Valor de {@code queue} incluido en el record.
 * @param deadLetterExchange Valor de {@code deadLetterExchange} incluido en el record.
 * @param deadLetterRoutingKey Valor de {@code deadLetterRoutingKey} incluido en el record.
 * @param deadLetterQueue Valor de {@code deadLetterQueue} incluido en el record.
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
     * Inicializa una instancia de {@code RabbitTopologyProperties}.
     *
     * @param exchange Valor de {@code exchange} utilizado por la operación.
     * @param routingKey Valor de {@code routingKey} utilizado por la operación.
     * @param queue Valor de {@code queue} utilizado por la operación.
     * @param deadLetterExchange Valor de {@code deadLetterExchange} utilizado por la operación.
     * @param deadLetterRoutingKey Valor de {@code deadLetterRoutingKey} utilizado por la operación.
     * @param deadLetterQueue Valor de {@code deadLetterQueue} utilizado por la operación.
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
     * Ejecuta la operación {@code requireText}.
     *
     * @param value Valor que debe procesarse.
     * @param property Valor de {@code property} utilizado por la operación.
     * @return Resultado producido por {@code requireText}.
     * @throws IllegalArgumentException Si los argumentos recibidos no cumplen las restricciones
     *     requeridas.
     */
    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("notification.rabbit." + property + " no puede estar vacío");
        }
        return value.strip();
    }
}
