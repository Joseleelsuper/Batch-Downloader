package es.ubu.batchdownloader.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/** Verifica el drenaje seguro de eventos plaintext anteriores al corte. */
class NotificationOutboxCutoverTest {
    @Test
    void encryptsPendingPlaintextBeforeOpeningThePublisherGate() throws Exception {
        UUID eventId = UUID.randomUUID();
        OutboxEventEntity event = OutboxEventEntity.pending(
                eventId,
                "user",
                UUID.randomUUID(),
                "notification.email.requested",
                "notification.email.requested",
                """
                {"payload":{"template":"PASSWORD_RESET","parameters":{
                  "username":"Ada","token":"legacy-plaintext-token"}}}
                """,
                Instant.EPOCH);
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.findPendingNotificationRequestsForUpdate()).thenReturn(List.of(event));
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            org.springframework.transaction.support.TransactionCallback<Integer> callback =
                    invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        ObjectMapper mapper = new ObjectMapper();
        NotificationTokenEnvelope envelope = new NotificationTokenEnvelope(
                Base64.getEncoder().encodeToString(new byte[32]));
        NotificationOutboxCutover cutover = new NotificationOutboxCutover(
                repository, mapper, envelope, transactions);

        cutover.run(mock(ApplicationArguments.class));

        String protectedToken = mapper.readTree(event.payload())
                .path("payload").path("parameters").path("token").asText();
        assertThat(protectedToken).startsWith(NotificationTokenEnvelope.VERSION_PREFIX);
        assertThat(envelope.decrypt(protectedToken)).isEqualTo("legacy-plaintext-token");
        assertThat(cutover.completed()).isTrue();
        verify(repository).save(event);
    }
}
