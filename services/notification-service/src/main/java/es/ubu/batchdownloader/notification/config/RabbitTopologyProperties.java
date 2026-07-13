package es.ubu.batchdownloader.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.rabbit")
public record RabbitTopologyProperties(
        String exchange,
        String routingKey,
        String queue,
        String deadLetterExchange,
        String deadLetterRoutingKey,
        String deadLetterQueue) {

    public RabbitTopologyProperties {
        exchange = requireText(exchange, "exchange");
        routingKey = requireText(routingKey, "routing-key");
        queue = requireText(queue, "queue");
        deadLetterExchange = requireText(deadLetterExchange, "dead-letter-exchange");
        deadLetterRoutingKey = requireText(deadLetterRoutingKey, "dead-letter-routing-key");
        deadLetterQueue = requireText(deadLetterQueue, "dead-letter-queue");
    }

    private static String requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("notification.rabbit." + property + " no puede estar vacío");
        }
        return value.strip();
    }
}
