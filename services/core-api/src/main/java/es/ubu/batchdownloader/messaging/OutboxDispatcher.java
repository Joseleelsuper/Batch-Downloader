package es.ubu.batchdownloader.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class OutboxDispatcher {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final OutboxEventRepository repository;
    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;
    private final String exchange;

    OutboxDispatcher(
            OutboxEventRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock,
            @Value("${app.messaging.exchange}") String exchange) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
        this.exchange = exchange;
    }

    @Scheduled(fixedDelayString = "${app.messaging.outbox-delay}")
    @Transactional
    public void publishPending() {
        for (OutboxEventEntity event : repository
                .findTop50ByPublishedAtIsNullAndNextAttemptAtLessThanEqualOrderByOccurredAtAsc(clock.instant())) {
            try {
                Message message = MessageBuilder
                        .withBody(event.payload().getBytes(StandardCharsets.UTF_8))
                        .setContentType("application/json")
                        .setContentEncoding(StandardCharsets.UTF_8.name())
                        .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                        .setMessageId(event.id().toString())
                        .setType(event.eventType())
                        .setCorrelationId(event.id().toString())
                        .build();
                rabbitTemplate.send(exchange, event.routingKey(), message);
                event.markPublished(clock.instant());
            } catch (RuntimeException exception) {
                event.markFailed(clock.instant(), exception);
                LOGGER.warn("Outbox publish failed eventId={} type={}", event.id(), event.eventType());
            }
        }
    }
}
