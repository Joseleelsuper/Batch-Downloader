package es.ubu.batchdownloader.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Implementa el componente {@code OutboxDispatcher}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
class OutboxDispatcher {
    /**
     * Constante que define {@code LOGGER}.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxDispatcher.class);
    /**
     * Estado {@code repository} mantenido por {@code OutboxDispatcher}.
     */
    private final OutboxEventRepository repository;
    /**
     * Estado {@code rabbitTemplate} mantenido por {@code OutboxDispatcher}.
     */
    private final RabbitTemplate rabbitTemplate;
    /**
     * Estado {@code clock} mantenido por {@code OutboxDispatcher}.
     */
    private final Clock clock;
    /**
     * Estado {@code exchange} mantenido por {@code OutboxDispatcher}.
     */
    private final String exchange;
    /** Tiempo tras el que una reclamación abandonada puede recuperarse. */
    private final Duration claimLease;
    /** Espera máxima del acuse de recibo del broker. */
    private final Duration confirmTimeout;
    /** Delimita las transacciones breves de reclamación y confirmación. */
    private final TransactionTemplate transactions;
    private final OutboxPayloadSanitizer payloadSanitizer;
    private final NotificationOutboxCutover notificationCutover;

    /**
     * Inicializa una instancia de {@code OutboxDispatcher}.
     *
     * @param repository Repositorio utilizado por la operación.
     * @param rabbitTemplate Valor de {@code rabbitTemplate} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     * @param exchange Valor de {@code exchange} utilizado por la operación.
     * @param claimLease Vigencia de una reclamación antes de poder recuperarla.
     * @param confirmTimeout Espera máxima del acuse de RabbitMQ.
     * @param transactions Gestor de las transacciones cortas del outbox.
     */
    OutboxDispatcher(
            OutboxEventRepository repository,
            RabbitTemplate rabbitTemplate,
            Clock clock,
            @Value("${app.messaging.exchange}") String exchange,
            @Value("${app.messaging.outbox-claim-lease}") Duration claimLease,
            @Value("${app.messaging.outbox-confirm-timeout}") Duration confirmTimeout,
            TransactionTemplate transactions,
            OutboxPayloadSanitizer payloadSanitizer,
            NotificationOutboxCutover notificationCutover) {
        this.repository = repository;
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
        this.exchange = exchange;
        this.claimLease = claimLease;
        this.confirmTimeout = confirmTimeout;
        this.transactions = transactions;
        this.payloadSanitizer = payloadSanitizer;
        this.notificationCutover = notificationCutover;
    }

    /**
     * Publica el contenido solicitado mediante {@code publishPending}.
     */
    @Scheduled(fixedDelayString = "${app.messaging.outbox-delay}")
    public void publishPending() {
        if (!notificationCutover.completed()) return;
        for (ClaimedEvent event : claimPending()) {
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
                publishAndConfirm(event, message);
                confirmPublished(event);
            } catch (RuntimeException exception) {
                confirmFailed(event, exception);
                LOGGER.warn("Outbox publish failed eventId={} type={}", event.id(), event.eventType());
            }
        }
    }

    /** Espera el acuse del broker sin mantener abierta ninguna transacción de MySQL. */
    private void publishAndConfirm(ClaimedEvent event, Message message) {
        CorrelationData correlation = new CorrelationData(event.id().toString());
        rabbitTemplate.send(exchange, event.routingKey(), message, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(
                    confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new IllegalStateException("rabbit_publish_not_confirmed");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("rabbit_publish_interrupted", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("rabbit_publish_not_confirmed", exception);
        }
    }

    /** Reclama hasta cincuenta eventos dentro de una única transacción corta. */
    private List<ClaimedEvent> claimPending() {
        Instant now = clock.instant();
        List<ClaimedEvent> claimed = transactions.execute(status -> repository
                .findClaimable(now, now.minus(claimLease)).stream()
                .map(event -> {
                    UUID token = UUID.randomUUID();
                    event.claim(token, now);
                    return ClaimedEvent.from(event, token);
                })
                .toList());
        return claimed == null ? List.of() : claimed;
    }

    /** Confirma el envío sin conservar la conexión durante la llamada a RabbitMQ. */
    private void confirmPublished(ClaimedEvent claim) {
        transactions.execute(status -> {
            repository.findByIdAndClaimToken(claim.id(), claim.token()).ifPresent(event -> {
                event.replacePayload(payloadSanitizer.afterPublish(event.eventType(), event.payload()));
                event.markPublished(clock.instant());
                repository.save(event);
            });
            return null;
        });
    }

    /** Libera la reclamación y programa el reintento en una transacción independiente. */
    private void confirmFailed(ClaimedEvent claim, RuntimeException exception) {
        transactions.execute(status -> {
            repository.findByIdAndClaimToken(claim.id(), claim.token()).ifPresent(event -> {
                event.markFailed(clock.instant(), exception);
                repository.save(event);
            });
            return null;
        });
    }

    /** Copia inmutable utilizada mientras no existe una transacción de base de datos. */
    private record ClaimedEvent(
            UUID id,
            UUID token,
            String eventType,
            String routingKey,
            String payload) {
        private static ClaimedEvent from(OutboxEventEntity event, UUID token) {
            return new ClaimedEvent(
                    event.id(), token, event.eventType(), event.routingKey(), event.payload());
        }
    }
}
