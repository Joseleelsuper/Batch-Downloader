package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.RateLimitException;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner;
import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DownloadJobServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    @Mock private DownloadJobStore jobs;
    @Mock private CatalogSourceLookup sources;
    @Mock private UserAccountStore users;
    @Mock private DownloadEventPublisher events;
    @Mock private DownloadJobNotifier notifier;
    @Mock private DownloadArtifactCleaner artifacts;

    private DownloadJobService service;

    @BeforeEach
    void setUp() {
        service = new DownloadJobService(
                jobs,
                sources,
                users,
                events,
                notifier,
                artifacts,
                (objectKey, validity) -> URI.create("https://storage.example.test/" + objectKey),
                Clock.fixed(NOW, ZoneOffset.UTC),
                100,
                Duration.ofHours(24),
                Duration.ofMinutes(5),
                2,
                10,
                30);
        lenient().when(jobs.save(any(DownloadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsPartialAnonymousJobAndDisablesEmailNotification() {
        UUID acceptedApp = UUID.randomUUID();
        UUID omittedApp = UUID.randomUUID();
        UUID sourceRef = UUID.randomUUID();
        when(sources.findVerifiedSources(any(), eq(List.of("windows"))))
                .thenReturn(Map.of(acceptedApp, new CatalogSourceLookup.VerifiedSource(
                        acceptedApp, sourceRef, "windows", "x86_64")));

        DownloadJobView view = service.create(
                new RequestOwner(null, "browser-hash", "ip-hash"),
                List.of(acceptedApp, omittedApp),
                List.of("windows"),
                true);

        assertThat(view.requestedCount()).isEqualTo(2);
        assertThat(view.acceptedCount()).isOne();
        assertThat(view.omittedCount()).isOne();
        ArgumentCaptor<DownloadJob> job = ArgumentCaptor.forClass(DownloadJob.class);
        verify(events).jobRequested(job.capture());
        assertThat(job.getValue().notifyWhenReady()).isFalse();
        assertThat(job.getValue().anonymousOwnerHash()).isEqualTo("browser-hash");
    }

    @Test
    void rejectsAnonymousCreationWhenItsActiveJobQuotaIsExhausted() {
        when(jobs.countAnonymousNonTerminal("browser-hash")).thenReturn(2L);

        assertThatThrownBy(() -> service.create(
                        new RequestOwner(null, "browser-hash", "ip-hash"),
                        List.of(UUID.randomUUID()),
                        List.of(),
                        false))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("m\u00e1ximo");

        verify(sources, never()).findVerifiedSources(any(), any());
    }

    @Test
    void expiresReadyJobsAndCleansEveryObjectUnderTheJobPrefix() {
        DownloadJob ready = DownloadJob.queue(
                UUID.randomUUID(),
                null,
                null,
                List.of(es.ubu.batchdownloader.downloads.domain.DownloadJobItem.queued(
                        UUID.randomUUID(), UUID.randomUUID(), NOW.minusSeconds(3600))),
                1,
                0,
                false,
                NOW.minusSeconds(3600),
                NOW.minusSeconds(1));
        ready.markReady(DownloadJobStatus.READY, "jobs/example/bundle.zip", NOW.minusSeconds(1), NOW.minusSeconds(1));
        when(jobs.findDownloadableExpiredBefore(NOW)).thenReturn(List.of(ready));

        service.expireReadyJobs();

        assertThat(ready.status()).isEqualTo(DownloadJobStatus.EXPIRED);
        verify(artifacts).deleteJobArtifacts(ready.id());
        verify(notifier).changed(any(DownloadJobView.class));
    }
}
