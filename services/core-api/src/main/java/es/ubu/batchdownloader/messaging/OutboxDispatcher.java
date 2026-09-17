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
 * Entrega a RabbitMQ eventos confirmados en MySQL usando reservas y transacciones cortas; espera el
 * acuse fuera de la transacción y reintenta los fallos sin perder el UUID del mensaje.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.messaging.OutboxWriter
 * @see es.ubu.batchdownloader.messaging.OutboxEventRepository
 * @see es.ubu.batchdownloader.messaging.NotificationOutboxCutover
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y retención
 */
@Component
class OutboxDispatcher {
    /**
     * Logger de la clase, usado para registrar decisiones sin exponer datos sensibles.
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
     * Compone persistencia, confirmación AMQP, duración de reservas y retirada de tokens tras el
     * acuse.
     *
     * @param repository Persistencia del outbox que participa en la transacción vigente.
     * @param rabbitTemplate Publicador AMQP con confirmación correlacionada y devolución de
     *     mensajes.
     * @param clock Reloj que fecha eventos, reservas, confirmaciones y próximos intentos.
     * @param exchange Nombre configurado del exchange duradero de publicación.
     * @param claimLease Duración tras la que otra ejecución puede recuperar una reserva no
     *     confirmada.
     * @param confirmTimeout Tiempo máximo de espera del acuse del broker por evento.
     * @param transactions Plantilla que delimita las transacciones cortas de migración, reserva o
     *     confirmación.
     * @param payloadSanitizer Retira tokens de entrega después de que RabbitMQ confirme la
     *     publicación.
     * @param notificationCutover Barrera que impide publicar antes de migrar los tokens pendientes
     *     a enc:v1.
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
     * Espera al corte de tokens, reclama hasta cincuenta eventos y publica mensajes persistentes.
     * Confirma o aplaza cada evento únicamente si conserva su reserva.
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

    /**
     * Envía el mensaje y espera el acuse correlacionado sin mantener una conexión MySQL. Un mensaje
     * devuelto o un acuse negativo se considera fallo.
     *
     * @param event Evento pendiente o copia reclamada cuyo contenido se procesa.
     * @param message Mensaje AMQP persistente construido desde la copia del evento reclamado.
     * @throws IllegalStateException si el broker no confirma a tiempo, devuelve el mensaje o se
     *     interrumpe la espera; la interrupción se conserva.
     */
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

    /**
     * Reserva los eventos disponibles con un token distinto para cada uno en una sola transacción
     * corta y copia su contenido para publicarlo después.
     *
     * @return hasta cincuenta copias reclamadas, o lista vacía.
     */
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

    /**
     * En una nueva transacción, retira el token de entrega y marca publicado el evento solo si su
     * token de reserva sigue coincidiendo.
     *
     * @param claim Copia inmutable con identidad y token de la reserva que se desea confirmar.
     */
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

    /**
     * En una nueva transacción, incrementa intentos y aplaza el evento únicamente si esta ejecución
     * todavía conserva la reserva.
     *
     * @param claim Copia inmutable con identidad y token de la reserva que se desea confirmar.
     * @param exception Fallo de publicación utilizado para aplazar el intento y registrar
     *     diagnóstico acotado.
     */
    private void confirmFailed(ClaimedEvent claim, RuntimeException exception) {
        transactions.execute(status -> {
            repository.findByIdAndClaimToken(claim.id(), claim.token()).ifPresent(event -> {
                event.markFailed(clock.instant(), exception);
                repository.save(event);
            });
            return null;
        });
    }

    /**
     * Copia identidad, ruta y carga del evento junto a su token de reserva para publicar fuera de
     * la transacción de lectura.
     *
     * @param id UUID estable del evento, conservado entre los reintentos.
     * @param token UUID de la reserva que debe conservarse al confirmar su resultado.
     * @param eventType Tipo de evento que identifica su contrato de carga.
     * @param routingKey Clave AMQP que selecciona los consumidores interesados.
     * @param payload Sobre JSON persistido o carga del evento antes de envolverla, según el punto
     *     del flujo.
     * @since 0.1.0
     * @version 0.1.0
     * @category Mensajería y retención
     */
    private record ClaimedEvent(
            UUID id,
            UUID token,
            String eventType,
            String routingKey,
            String payload) {
        /**
         * Copia los datos necesarios para el envío sin conservar una entidad JPA gestionada.
         *
         * @param event Evento pendiente o copia reclamada cuyo contenido se procesa.
         * @param token UUID de la reserva que debe conservarse al confirmar su resultado.
         * @return instantánea ligada a la reserva recibida.
         */
        private static ClaimedEvent from(OutboxEventEntity event, UUID token) {
            return new ClaimedEvent(
                    event.id(), token, event.eventType(), event.routingKey(), event.payload());
        }
    }
}
