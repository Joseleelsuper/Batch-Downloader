package es.ubu.batchdownloader.downloadworker.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobFailedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;

class DownloadJobFailureRecovererTest {
    @Test
    void publishesATerminalFailureBeforeRejectingTheCommandToItsDlq() throws Exception {
        UUID jobId = UUID.randomUUID();
        DownloadJobRequestedEvent requested = new DownloadJobRequestedEvent(
                UUID.randomUUID(),
                EventTypes.JOB_REQUESTED,
                EventTypes.CURRENT_VERSION,
                Instant.parse("2026-07-11T11:00:00Z"),
                UUID.randomUUID().toString(),
                null,
                new DownloadJobPayload(
                        jobId,
                        List.of(new DownloadItemRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID()))));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        RecordingPublisher publisher = new RecordingPublisher();
        DownloadJobFailureRecoverer recoverer = new DownloadJobFailureRecoverer(
                mapper,
                publisher,
                Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> recoverer.recover(
                        new Message(mapper.writeValueAsBytes(requested)),
                        new IllegalStateException("transient failure")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("download_job_processing_failed");

        assertThat(publisher.routingKeys).containsExactly(EventTypes.JOB_FAILED_ROUTING_KEY);
        DownloadJobFailedEvent failed = (DownloadJobFailedEvent) publisher.events.getFirst();
        assertThat(failed.payload().jobId()).isEqualTo(jobId);
        assertThat(failed.payload().errorCode()).isEqualTo("download_job_processing_failed");
    }

    @Test
    void requeuesInsteadOfDeadLetteringWhenTheTerminalFailureCannotBePublished() throws Exception {
        DownloadJobRequestedEvent requested = new DownloadJobRequestedEvent(
                UUID.randomUUID(),
                EventTypes.JOB_REQUESTED,
                EventTypes.CURRENT_VERSION,
                Instant.parse("2026-07-11T11:00:00Z"),
                UUID.randomUUID().toString(),
                null,
                new DownloadJobPayload(
                        UUID.randomUUID(),
                        List.of(new DownloadItemRequest(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID()))));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        DownloadJobFailureRecoverer recoverer = new DownloadJobFailureRecoverer(
                mapper,
                (routingKey, event) -> {
                    throw new IllegalStateException("event broker unavailable");
                },
                Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> recoverer.recover(
                        new Message(mapper.writeValueAsBytes(requested)),
                        new IllegalStateException("processing failure")))
                .isInstanceOf(ImmediateRequeueAmqpException.class);
    }

    private static final class RecordingPublisher implements EventPublisher {
        private final List<String> routingKeys = new ArrayList<>();
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publish(String routingKey, Object event) {
            routingKeys.add(routingKey);
            events.add(event);
        }
    }
}
