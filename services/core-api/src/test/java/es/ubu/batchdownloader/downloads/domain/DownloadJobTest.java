package es.ubu.batchdownloader.downloads.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code DownloadJobTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class DownloadJobTest {
    /**
     * Constante que define {@code NOW}.
     */
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    /** Comprueba que un item manual no invente un identificador de fuente. */
    @Test
    void manualItemPreservesOfficialPageWithoutSourceReference() {
        UUID appId = UUID.randomUUID();

        DownloadJobItem item = DownloadJobItem.manual(
                appId, "Aplicación manual", "https://example.com/manual", NOW);

        assertThat(item.appId()).isEqualTo(appId);
        assertThat(item.sourceRef()).isNull();
        assertThat(item.officialPageUrl()).isEqualTo("https://example.com/manual");
    }

    /**
     * Comprueba el escenario {@code preservesRequestedAcceptedAndOmittedCountsForPartialJobs}.
     */
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

    /**
     * Comprueba el escenario {@code
     * cancellationIsTerminalAndCannotBeOverwrittenByLateWorkerEvents}.
     */
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

    /**
     * Comprueba el escenario {@code requiresExactlyOneOwner}.
     */
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

    /**
     * Comprueba el escenario {@code
     * advancesTerminalItemsIndividuallyAndKeepsPackagingBelowOneHundred}.
     */
    @Test
    void advancesTerminalItemsIndividuallyAndKeepsPackagingBelowOneHundred() {
        DownloadJobItem first = DownloadJobItem.queued(UUID.randomUUID(), UUID.randomUUID(), NOW);
        DownloadJobItem second = DownloadJobItem.queued(UUID.randomUUID(), UUID.randomUUID(), NOW);
        DownloadJob job = DownloadJob.queue(
                UUID.randomUUID(),
                null,
                null,
                List.of(first, second),
                2,
                0,
                false,
                NOW,
                NOW.plusSeconds(3600));

        job.updateItem(first.id(), DownloadItemStatus.COMPLETED, 12, "sha", null, NOW.plusSeconds(1));
        assertThat(job.progress()).isEqualTo(45);
        assertThat(job.status()).isEqualTo(DownloadJobStatus.DOWNLOADING);

        job.updateItem(second.id(), DownloadItemStatus.FAILED, 0, null, "remote_http_404", NOW.plusSeconds(2));
        assertThat(job.progress()).isEqualTo(90);
        assertThat(job.status()).isEqualTo(DownloadJobStatus.PACKAGING);

        job.markReady(
                DownloadJobStatus.MANUAL_ONLY,
                "jobs/example/bundle.zip",
                NOW.plusSeconds(1800),
                NOW.plusSeconds(3));
        assertThat(job.progress()).isEqualTo(100);
        assertThat(job.status()).isEqualTo(DownloadJobStatus.MANUAL_ONLY);
    }
}
