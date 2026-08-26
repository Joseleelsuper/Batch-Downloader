package es.ubu.batchdownloader.downloads.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import es.ubu.batchdownloader.messaging.OutboxWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Agrupa los escenarios de prueba de {@code DownloadOutboxPublisherTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class DownloadOutboxPublisherTest {
    /**
     * Comprueba el escenario {@code publishesOnlyJobAndSourceIdentifiersForWorkerResolution}.
     */
    @Test
    void publishesOnlyJobAndSourceIdentifiersForWorkerResolution() {
        OutboxWriter outbox = Mockito.mock(OutboxWriter.class);
        DownloadJobItem item = DownloadJobItem.queued(UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        DownloadJob job = DownloadJob.queue(
                UUID.randomUUID(), null, null, List.of(item), 1, 0, false,
                Instant.now(), Instant.now().plusSeconds(3600));

        new DownloadOutboxPublisher(outbox).jobRequested(job);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(
                eq("download-job"), eq(job.id()), eq("download.job.requested"),
                eq("download.job.requested"), eq(job.id()), any(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        assertThat(payload).containsOnlyKeys("jobId", "items");
        @SuppressWarnings("unchecked")
        List<Map<String, UUID>> items = (List<Map<String, UUID>>) payload.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("itemId", item.id())
                .containsEntry("appId", item.appId())
                .containsEntry("sourceRef", item.sourceRef());
    }

    /** Publica una fuente nula de forma explícita para los accesos manuales. */
    @Test
    void publishesManualItemsWithoutInventingSourceIdentifiers() {
        OutboxWriter outbox = Mockito.mock(OutboxWriter.class);
        DownloadJobItem item = DownloadJobItem.manual(
                UUID.randomUUID(), "Aplicación manual", "https://example.com", Instant.now());
        DownloadJob job = DownloadJob.queue(
                UUID.randomUUID(), null, null, List.of(item), 1, 0, false,
                Instant.now(), Instant.now().plusSeconds(3600));

        new DownloadOutboxPublisher(outbox).jobRequested(job);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outbox).append(
                eq("download-job"), eq(job.id()), eq("download.job.requested"),
                eq("download.job.requested"), eq(job.id()), any(), payloadCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) payloadCaptor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        assertThat(items.getFirst()).containsEntry("sourceRef", null);
    }
}
