package es.ubu.batchdownloader.downloads.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

/**
 * Agrupa los escenarios de prueba de {@code DownloadWorkerEventListenerTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class DownloadWorkerEventListenerTest {
    /**
     * Comprueba el escenario {@code rejectsMalformedMessagesWithoutClaimingAnInboxRecord}.
     */
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

    /**
     * Comprueba el escenario {@code letsTransientJobApplicationFailuresReachRabbitRetryHandling}.
     */
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

    @Test
    void recordsCompletedItemsWhenAnAuthenticatedJobBecomesReady() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO core_inbox_messages"), any(Object[].class)))
                .thenReturn(1);
        DownloadJobService jobs = Mockito.mock(DownloadJobService.class);
        UUID jobId = UUID.randomUUID();
        DownloadWorkerEventListener listener = listener(jdbc, jobs);

        listener.receive(message(ready(jobId)));

        verify(jobs).applyReady(
                org.mockito.ArgumentMatchers.eq(jobId),
                org.mockito.ArgumentMatchers.eq(es.ubu.batchdownloader.downloads.domain.DownloadJobStatus.READY),
                org.mockito.ArgumentMatchers.eq("zips/final.zip"),
                org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-09T12:00:00Z")));
        verify(jdbc).update(startsWith("INSERT IGNORE INTO user_download_history"), any(Object[].class));
        verify(jdbc).update(startsWith("UPDATE core_inbox_messages"), any(Object[].class));
    }

    @Test
    void ignoresDuplicateReadyEventsBeforeHistoryCanBeInsertedAgain() {
        JdbcTemplate jdbc = Mockito.mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("INSERT IGNORE INTO core_inbox_messages"), any(Object[].class)))
                .thenReturn(0);
        DownloadJobService jobs = Mockito.mock(DownloadJobService.class);
        DownloadWorkerEventListener listener = listener(jdbc, jobs);

        listener.receive(message(ready(UUID.randomUUID())));

        verifyNoInteractions(jobs);
        verify(jdbc, never()).update(startsWith("INSERT IGNORE INTO user_download_history"), any(Object[].class));
    }

    /**
     * Enumera los elementos solicitados mediante {@code listener}.
     *
     * @param jdbc Valor de {@code jdbc} utilizado por la operación.
     * @param jobs Valor de {@code jobs} utilizado por la operación.
     * @return Resultado producido por {@code listener}.
     */
    private DownloadWorkerEventListener listener(JdbcTemplate jdbc, DownloadJobService jobs) {
        return new DownloadWorkerEventListener(
                new ObjectMapper(), jdbc, jobs, Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC));
    }

    /**
     * Ejecuta la operación {@code message}.
     *
     * @param json Valor de {@code json} utilizado por la operación.
     * @return Resultado producido por {@code message}.
     */
    private Message message(String json) {
        return new Message(json.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    /**
     * Ejecuta la operación {@code progressed}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param itemId Identificador de {@code item} utilizado por la operación.
     * @return Resultado producido por {@code progressed}.
     */
    private String progressed(UUID jobId, UUID itemId) {
        return """
                {"eventId":"%s","type":"download.job.progressed","schemaVersion":1,
                 "payload":{"jobId":"%s","itemId":"%s","status":"DOWNLOADING","bytesDownloaded":42}}
                """.formatted(UUID.randomUUID(), jobId, itemId);
    }

    private String ready(UUID jobId) {
        return """
                {"eventId":"%s","type":"download.job.ready","schemaVersion":1,
                 "payload":{"jobId":"%s","status":"READY","objectKey":"zips/final.zip",
                 "expiresAt":"2026-08-09T12:00:00Z"}}
                """.formatted(UUID.randomUUID(), jobId);
    }
}
