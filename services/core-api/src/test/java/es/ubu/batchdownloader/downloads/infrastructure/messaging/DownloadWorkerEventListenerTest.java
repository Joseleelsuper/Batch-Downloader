package es.ubu.batchdownloader.downloads.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.jdbc.core.JdbcTemplate;

class DownloadWorkerEventListenerTest {
    @Test
    void rejectsMalformedMessagesWithoutClaimingAnInboxRecord() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        DownloadJobService jobs = Mockito.mock(DownloadJobService.class);
        DownloadWorkerEventListener listener = listener(jdbc, jobs);

        assertThatThrownBy(() -> listener.receive(message("not-json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("invalid_download_event");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void letsTransientJobApplicationFailuresReachRabbitRetryHandling() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT IGNORE"), any(Object[].class))).thenReturn(1);
        DownloadJobService jobs = Mockito.mock(DownloadJobService.class);
        UUID jobId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        Mockito.doThrow(new IllegalStateException("database temporarily unavailable"))
                .when(jobs)
                .applyProgress(any(), any(), any(), Mockito.anyLong(), any(), any());
        DownloadWorkerEventListener listener = listener(jdbc, jobs);

        assertThatThrownBy(() -> listener.receive(message(progressed(jobId, itemId))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("temporarily unavailable");

        verify(jdbc, never()).update(startsWith("UPDATE core_inbox_messages"), any(Object[].class));
    }

    private DownloadWorkerEventListener listener(JdbcTemplate jdbc, DownloadJobService jobs) {
        return new DownloadWorkerEventListener(
                new ObjectMapper(), jdbc, jobs, Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC));
    }

    private Message message(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    private String progressed(UUID jobId, UUID itemId) {
        return """
                {"eventId":"%s","type":"download.job.progressed","schemaVersion":1,
                 "payload":{"jobId":"%s","itemId":"%s","status":"DOWNLOADING","bytesDownloaded":42}}
                """.formatted(UUID.randomUUID(), jobId, itemId);
    }
}
