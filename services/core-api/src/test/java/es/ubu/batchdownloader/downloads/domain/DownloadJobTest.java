package es.ubu.batchdownloader.downloads.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DownloadJobTest {
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    @Test
    void preservesRequestedAcceptedAndOmittedCountsForPartialJobs() {
        DownloadJob job = DownloadJob.queue(
                null,
                "browser-hash",
                "ip-hash",
                List.of(DownloadJobItem.queued(UUID.randomUUID(), UUID.randomUUID(), NOW)),
                2,
                1,
                true,
                NOW,
                NOW.plusSeconds(3600));

        assertThat(job.requestedCount()).isEqualTo(2);
        assertThat(job.acceptedCount()).isOne();
        assertThat(job.omittedCount()).isOne();
        assertThat(job.notifyWhenReady()).isTrue();
        assertThat(job.anonymousOwnerHash()).isEqualTo("browser-hash");
    }

    @Test
    void cancellationIsTerminalAndCannotBeOverwrittenByLateWorkerEvents() {
        DownloadJob job = DownloadJob.queue(
                UUID.randomUUID(),
                null,
                null,
                List.of(DownloadJobItem.queued(UUID.randomUUID(), UUID.randomUUID(), NOW)),
                1,
                0,
                false,
                NOW,
                NOW.plusSeconds(3600));

        assertThat(job.requestCancellation(NOW.plusSeconds(1))).isTrue();
        job.markReady(DownloadJobStatus.READY, "jobs/example/bundle.zip", NOW.plusSeconds(1800), NOW.plusSeconds(2));

        assertThat(job.status()).isEqualTo(DownloadJobStatus.CANCELLED);
        assertThat(job.items()).allMatch(item -> item.status() == DownloadItemStatus.CANCELLED);
    }

    @Test
    void requiresExactlyOneOwner() {
        assertThatThrownBy(() -> DownloadJob.queue(
                        UUID.randomUUID(),
                        "browser-hash",
                        null,
                        List.of(DownloadJobItem.queued(UUID.randomUUID(), UUID.randomUUID(), NOW)),
                        1,
                        0,
                        false,
                        NOW,
                        NOW.plusSeconds(3600)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("download_job_requires_exactly_one_owner");
    }
}
