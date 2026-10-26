package es.ubu.batchdownloader.downloads.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;
import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Verifica coalescing y seguimiento hasta que se confirma la purga del trabajo. */
class SseDownloadJobNotifierTest {
    private final DownloadJobStore jobs = mock(DownloadJobStore.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DownloadStorageCoordinator> storage = mock(ObjectProvider.class);

    /** El SSE entrega el código concreto que debe poder explicar el navegador. */
    @Test
    void sendsTheSpecificProviderFailureInTheJobEvent() throws IOException {
        Instant now = Instant.parse("2026-10-26T12:00:00Z");
        var item = DownloadJobItem.queued(UUID.randomUUID(), UUID.randomUUID(), now);
        var job = DownloadJob.queue(UUID.randomUUID(), null, null,
                List.of(item), 1, 0, now, now.plusSeconds(3600));
        job.updateItem(item.id(), DownloadItemStatus.FAILED, 0, null,
                "source_revalidation_http_618", now.plusSeconds(1));
        SseEmitter emitter = mock(SseEmitter.class);
        var notifier = new SseDownloadJobNotifier(Duration.ofHours(1), () -> emitter, jobs, storage);
        try {
            notifier.subscribe(DownloadJobView.from(job));
            var event = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
            verify(emitter).send(event.capture());
            assertThat(event.getValue().build()).anySatisfy(data -> {
                assertThat(data.getData()).isInstanceOf(DownloadJobView.class);
                var delivered = (DownloadJobView) data.getData();
                assertThat(delivered.items().getFirst().errorCode()).isEqualTo("source_revalidation_http_618");
            });
        } finally {
            notifier.close();
        }
    }

    @Test
    void coalescesProgressAndKeepsReadyConnectedUntilCleanup() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        SseDownloadJobNotifier notifier = new SseDownloadJobNotifier(
                Duration.ofHours(1), () -> emitter, jobs, storage);
        UUID jobId = UUID.randomUUID();
        try {
            notifier.subscribe(view(jobId, DownloadJobStatus.QUEUED, 0));
            for (int progress = 1; progress <= 10; progress++) {
                notifier.changed(view(jobId, DownloadJobStatus.DOWNLOADING, progress));
            }

            verify(emitter, timeout(1_500).times(2))
                    .send(any(SseEmitter.SseEventBuilder.class));

            notifier.changed(view(jobId, DownloadJobStatus.READY, 100));

            verify(emitter, timeout(200).times(3))
                    .send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter, never()).complete();
            notifier.removed(jobId);
            verify(emitter, timeout(1_000)).complete();
        } finally {
            notifier.close();
        }
    }

    @Test
    void heartbeatClosesSubscribersWhoseJobWasPurged() {
        SseEmitter emitter = mock(SseEmitter.class);
        UUID jobId = UUID.randomUUID();
        when(jobs.findById(jobId)).thenReturn(Optional.empty());
        SseDownloadJobNotifier notifier = new SseDownloadJobNotifier(
                Duration.ofHours(1), () -> emitter, jobs, storage);
        try {
            notifier.subscribe(view(jobId, DownloadJobStatus.READY, 100));
            notifier.heartbeat();
            verify(emitter, timeout(1_000)).complete();
            verify(storage, never()).getObject();
        } finally {
            notifier.close();
        }
    }

    @Test
    void heartbeatKeepsAQueuedObserverConnectedAndRefreshesItsView() {
        UUID jobId = UUID.randomUUID();
        SseEmitter emitter = mock(SseEmitter.class);
        DownloadJob job = mock(DownloadJob.class);
        DownloadStorageCoordinator coordinator = mock(DownloadStorageCoordinator.class);
        when(job.id()).thenReturn(jobId);
        when(job.status()).thenReturn(DownloadJobStatus.QUEUED);
        when(job.items()).thenReturn(List.of());
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(storage.getObject()).thenReturn(coordinator);
        when(coordinator.decorate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        SseDownloadJobNotifier notifier = new SseDownloadJobNotifier(
                Duration.ofHours(1), () -> emitter, jobs, storage);
        try {
            notifier.subscribe(view(jobId, DownloadJobStatus.QUEUED, 0));
            notifier.heartbeat();
            verify(coordinator).touch(jobId, "waiting", 0);
            verify(coordinator).decorate(any(DownloadJobView.class));
            verify(emitter, never()).complete();
        } finally {
            notifier.close();
        }
    }

    private DownloadJobView view(UUID jobId, DownloadJobStatus status, int progress) {
        Instant now = Instant.parse("2026-08-05T00:00:00Z");
        return new DownloadJobView(
                jobId,
                status,
                progress,
                1,
                1,
                0,
                null,
                List.of(),
                now,
                now.plus(Duration.ofHours(24)));
    }
}
