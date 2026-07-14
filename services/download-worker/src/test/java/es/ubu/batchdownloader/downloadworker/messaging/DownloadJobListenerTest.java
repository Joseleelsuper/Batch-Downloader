package es.ubu.batchdownloader.downloadworker.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.ports.InboxRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.util.unit.DataSize;

class DownloadJobListenerTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final InboxRepository inbox = mock(InboxRepository.class);
    private final DownloadJobProcessor processor = mock(DownloadJobProcessor.class);
    private final DownloadProperties properties = new DownloadProperties(
            10,
            DataSize.ofMegabytes(10),
            DataSize.ofMegabytes(20),
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            2,
            Duration.ofMinutes(5),
            "/tmp");
    private final DownloadJobListener listener = new DownloadJobListener(
            validator, inbox, processor, properties);

    @Test
    void skipsAlreadyProcessedEvent() {
        DownloadJobRequestedEvent event = event(EventTypes.CURRENT_VERSION);
        when(inbox.tryStart(event.eventId(), properties.inboxLease())).thenReturn(false);

        listener.receive(event);

        verify(processor, never()).process(event);
        verify(inbox, never()).complete(event.eventId());
    }

    @Test
    void completesInboxOnlyAfterSuccessfulProcessing() {
        DownloadJobRequestedEvent event = event(EventTypes.CURRENT_VERSION);
        when(inbox.tryStart(event.eventId(), properties.inboxLease())).thenReturn(true);

        listener.receive(event);

        verify(processor).process(event);
        verify(inbox).complete(event.eventId());
    }

    @Test
    void rejectsUnsupportedVersionBeforeClaimingInbox() {
        DownloadJobRequestedEvent event = event(2);

        assertThatThrownBy(() -> listener.receive(event))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("unsupported_download_event_version");
        verify(inbox, never()).tryStart(event.eventId(), properties.inboxLease());
    }

    @Test
    void releasesInboxWhenProcessingFailsSoRabbitCanRetry() {
        DownloadJobRequestedEvent event = event(EventTypes.CURRENT_VERSION);
        when(inbox.tryStart(event.eventId(), properties.inboxLease())).thenReturn(true);
        doThrow(new IllegalStateException("storage unavailable")).when(processor).process(event);

        assertThatThrownBy(() -> listener.receive(event)).isInstanceOf(IllegalStateException.class);

        verify(inbox).release(event.eventId());
        verify(inbox, never()).complete(event.eventId());
    }

    private DownloadJobRequestedEvent event(int version) {
        return new DownloadJobRequestedEvent(
                UUID.randomUUID(),
                EventTypes.JOB_REQUESTED,
                version,
                Instant.now(),
                UUID.randomUUID().toString(),
                null,
                new DownloadJobPayload(
                        UUID.randomUUID(),
                        List.of(new DownloadItemRequest(
                                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))));
    }
}
