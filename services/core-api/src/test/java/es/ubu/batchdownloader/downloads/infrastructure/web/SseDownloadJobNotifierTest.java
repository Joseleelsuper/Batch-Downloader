package es.ubu.batchdownloader.downloads.infrastructure.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Verifica coalescing de 4 Hz y entrega inmediata de estados terminales. */
class SseDownloadJobNotifierTest {
    @Test
    void coalescesProgressAndSendsTerminalStateImmediately() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        SseDownloadJobNotifier notifier = new SseDownloadJobNotifier(
                Duration.ofHours(1), () -> emitter);
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
            verify(emitter).complete();
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
