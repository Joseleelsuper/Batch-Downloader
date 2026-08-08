package es.ubu.batchdownloader.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifica publicación idempotente sin mantener abierta la transacción de lectura. */
class OutboxDispatcherTest {
    @Test
    void publishesOutsideTransactionWithStableMessageId() {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = OutboxEventEntity.pending(
                eventId,
                "download",
                UUID.randomUUID(),
                "download.job.requested",
                "download.job.requested",
                "{}",
                now);
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        Duration lease = Duration.ofMinutes(5);
        when(repository.findClaimable(now, now.minus(lease)))
                .thenReturn(List.of(event));
        when(repository.findByIdAndClaimToken(eq(eventId), any(UUID.class)))
                .thenReturn(Optional.of(event));
        AtomicBoolean inTransaction = new AtomicBoolean();
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.transaction.support.TransactionCallback<Object> callback =
                    invocation.getArgument(0);
            assertThat(inTransaction.compareAndSet(false, true)).isTrue();
            try {
                return callback.doInTransaction(mock(TransactionStatus.class));
            } finally {
                inTransaction.set(false);
            }
        });
        doAnswer(invocation -> {
                    assertThat(inTransaction.get()).isFalse();
                    Message message = invocation.getArgument(2);
                    assertThat(message.getMessageProperties().getMessageId())
                            .isEqualTo(eventId.toString());
                    CorrelationData correlation = invocation.getArgument(3);
                    correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
                    return null;
                })
                .when(rabbit)
                .send(
                        eq("batch.commands"),
                        eq("download.job.requested"),
                        any(Message.class),
                        any(CorrelationData.class));
        OutboxDispatcher dispatcher = new OutboxDispatcher(
                repository,
                rabbit,
                Clock.fixed(now, ZoneOffset.UTC),
                "batch.commands",
                lease,
                Duration.ofSeconds(2),
                transactions,
                new OutboxPayloadSanitizer(new ObjectMapper()));

        dispatcher.publishPending();

        var ordered = inOrder(repository, rabbit);
        ordered.verify(repository).findClaimable(now, now.minus(lease));
        ordered.verify(rabbit).send(
                eq("batch.commands"),
                eq("download.job.requested"),
                any(Message.class),
                any(CorrelationData.class));
        ordered.verify(repository).findByIdAndClaimToken(eq(eventId), any(UUID.class));
        verify(repository).save(event);
    }
}
