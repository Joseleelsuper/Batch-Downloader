package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.RateLimitException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner;
import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agrupa los escenarios de prueba de {@code DownloadJobServiceTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@ExtendWith(MockitoExtension.class)
class DownloadJobServiceTest {
    /**
     * Constante que define {@code NOW}.
     */
    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    /**
     * Dato compartido {@code jobs} para los escenarios de prueba.
     */
    @Mock private DownloadJobStore jobs;
    /**
     * Dato compartido {@code sources} para los escenarios de prueba.
     */
    @Mock private CatalogSourceLookup sources;
    /**
     * Dato compartido {@code users} para los escenarios de prueba.
     */
    @Mock private UserAccountStore users;
    /**
     * Dato compartido {@code events} para los escenarios de prueba.
     */
    @Mock private DownloadEventPublisher events;
    /**
     * Dato compartido {@code notifier} para los escenarios de prueba.
     */
    @Mock private DownloadJobNotifier notifier;
    /**
     * Dato compartido {@code artifacts} para los escenarios de prueba.
     */
    @Mock private DownloadArtifactCleaner artifacts;

    /**
     * Dato compartido {@code service} para los escenarios de prueba.
     */
    private DownloadJobService service;

    /**
     * Prepara el estado necesario para los escenarios de prueba.
     */
    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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
                30,
                3,
                100,
                new TransactionTemplate(transactionManager));
        lenient().when(jobs.save(any(DownloadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Comprueba el escenario {@code createsPartialAnonymousJobAndDisablesEmailNotification}.
     */
    @Test
    void createsPartialAnonymousJobAndDisablesEmailNotification() {
        UUID acceptedApp = UUID.randomUUID();
        UUID omittedApp = UUID.randomUUID();
        UUID sourceRef = UUID.randomUUID();
        when(sources.findVerifiedSources(any(), eq(List.of("windows"))))
                .thenReturn(Map.of(acceptedApp, new CatalogSourceLookup.VerifiedSource(
                        acceptedApp,
                        sourceRef,
                        "windows",
                        "x86_64",
                        "Aplicación aceptada",
                        "https://example.com/app")));

        DownloadJobView view = service.create(
                new RequestOwner(null, "browser-hash", "ip-hash"),
                List.of(acceptedApp, omittedApp),
                List.of("windows"),
                true);

        assertThat(view.requestedCount()).isEqualTo(2);
        assertThat(view.acceptedCount()).isOne();
        assertThat(view.omittedCount()).isOne();
        assertThat(view.items().getFirst().appName()).isEqualTo("Aplicación aceptada");
        assertThat(view.items().getFirst().officialPageUrl()).isEqualTo("https://example.com/app");
        ArgumentCaptor<DownloadJob> job = ArgumentCaptor.forClass(DownloadJob.class);
        verify(events).jobRequested(job.capture());
        assertThat(job.getValue().notifyWhenReady()).isFalse();
        assertThat(job.getValue().anonymousOwnerHash()).isEqualTo("browser-hash");
    }

    /**
     * Comprueba el escenario {@code rejectsAnonymousCreationWhenItsActiveJobQuotaIsExhausted}.
     */
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

    /**
     * Comprueba el escenario {@code publishesReadyStateOnlyAfterTheSurroundingTransactionCommits}.
     */
    @Test
    void publishesReadyStateOnlyAfterTheSurroundingTransactionCommits() {
        DownloadJob job = DownloadJob.queue(
                UUID.randomUUID(),
                null,
                null,
                List.of(DownloadJobItem.queued(
                        UUID.randomUUID(), UUID.randomUUID(), NOW)),
                1,
                0,
                false,
                NOW,
                NOW.plusSeconds(3600));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.applyReady(
                    job.id(),
                    DownloadJobStatus.READY,
                    "jobs/example/bundle.zip",
                    NOW.plusSeconds(3600));

            verify(notifier, never()).changed(any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(notifier).changed(any(DownloadJobView.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * Comprueba el escenario {@code expiresReadyJobsAndCleansEveryObjectUnderTheJobPrefix}.
     */
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
        doThrow(new IllegalStateException("minio unavailable"))
                .doThrow(new IllegalStateException("minio unavailable"))
                .doNothing()
                .when(artifacts).deleteJobArtifacts(ready.id());

        service.expireReadyJobs();

        assertThat(ready.status()).isEqualTo(DownloadJobStatus.EXPIRED);
        verify(artifacts, times(3)).deleteJobArtifacts(ready.id());
        verify(notifier).changed(any(DownloadJobView.class));
    }

    /**
     * Comprueba el escenario {@code returnsAllRequestedItemMetadataWithoutPartialResponses}.
     */
    @Test
    void returnsAllRequestedItemMetadataWithoutPartialResponses() {
        DownloadJob job = DownloadJob.queue(
                UUID.randomUUID(),
                null,
                null,
                List.of(
                        DownloadJobItem.queued(
                                UUID.randomUUID(), UUID.randomUUID(), "Primera", "https://example.com/one", NOW),
                        DownloadJobItem.queued(
                                UUID.randomUUID(), UUID.randomUUID(), "Segunda", null, NOW)),
                2,
                0,
                false,
                NOW,
                NOW.plusSeconds(3600));
        UUID firstId = job.items().get(0).id();
        UUID secondId = job.items().get(1).id();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(service.itemMetadata(job.id(), List.of(secondId, firstId)))
                .extracting(DownloadJobService.DownloadItemMetadata::appName)
                .containsExactly("Segunda", "Primera");

        UUID foreignItem = UUID.randomUUID();
        assertThatThrownBy(() -> service.itemMetadata(job.id(), List.of(firstId, foreignItem)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No existe el trabajo.");
    }
}
