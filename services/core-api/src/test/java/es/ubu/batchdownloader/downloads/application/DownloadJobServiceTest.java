package es.ubu.batchdownloader.downloads.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.common.RateLimitException;
import es.ubu.batchdownloader.common.ServiceUnavailableException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.downloads.application.DownloadRequestOwner.RequestOwner;
import es.ubu.batchdownloader.downloads.application.port.CatalogSourceLookup;
import es.ubu.batchdownloader.downloads.application.port.DownloadArtifactCleaner;
import es.ubu.batchdownloader.downloads.application.port.DownloadEventPublisher;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
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
     * Valor compartido que fija n o w para el comportamiento del componente.
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
    private DownloadJobAccessService access;
    private DownloadJobEventHandler handler;
    private DownloadJobExpiration expiration;

    /**
     * Prepara el estado necesario para los escenarios de prueba.
     */
    @BeforeEach
    void setUp() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().when(sources.findManualSources(any())).thenReturn(Map.of());
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        DownloadLimits limits = new DownloadLimits(100, Duration.ofHours(24), Duration.ofMinutes(5),
                2, 10, 30, 3, 50);
        DownloadJobNotifications notifications = new DownloadJobNotifications(notifier);
        service = new DownloadJobService(jobs, sources, events, clock, limits);
        access = new DownloadJobAccessService(jobs,
                (objectKey, validity) -> URI.create("https://storage.example.test/" + objectKey),
                clock, limits, events, notifications);
        handler = new DownloadJobEventHandler(jobs, clock, limits, notifications);
        expiration = new DownloadJobExpiration(jobs, artifacts, notifier, clock,
                new TransactionTemplate(transactionManager));
        lenient().when(jobs.save(any(DownloadJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * Comprueba la creación de un trabajo anónimo parcial.
     */
    @Test
    void createsPartialAnonymousJobWithOmittedItem() {
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

        DownloadJobView view = service.create(new RequestOwner(null, "browser-hash", "ip-hash"), new DownloadSelection(List.of(acceptedApp, omittedApp), List.of("windows"), null, null, null));

        assertThat(view.requestedCount()).isEqualTo(2);
        assertThat(view.acceptedCount()).isOne();
        assertThat(view.omittedCount()).isOne();
        assertThat(view.items().getFirst().appName()).isEqualTo("Aplicación aceptada");
        assertThat(view.items().getFirst().officialPageUrl()).isEqualTo("https://example.com/app");
        ArgumentCaptor<DownloadJob> job = ArgumentCaptor.forClass(DownloadJob.class);
        verify(events).jobRequested(job.capture());
        assertThat(job.getValue().anonymousOwnerHash()).isEqualTo("browser-hash");
    }

    /** Conserva en el trabajo las aplicaciones que requieren acudir a su página oficial. */
    @Test
    void createsManualItemsForAppsWithoutVerifiedInstaller() {
        UUID downloadableApp = UUID.randomUUID();
        UUID manualApp = UUID.randomUUID();
        UUID sourceRef = UUID.randomUUID();
        when(sources.findVerifiedSources(any(), eq(List.of("windows"))))
                .thenReturn(Map.of(downloadableApp, new CatalogSourceLookup.VerifiedSource(
                        downloadableApp,
                        sourceRef,
                        "windows",
                        "x86_64",
                        "Aplicación descargable",
                        "https://example.com/downloadable")));
        when(sources.findManualSources(any())).thenReturn(Map.of(
                manualApp,
                new CatalogSourceLookup.ManualSource(
                        manualApp, "Aplicación manual", "https://example.com/manual")));

        DownloadJobView view = service.create(new RequestOwner(null, "browser-hash", "ip-hash"), new DownloadSelection(List.of(downloadableApp, manualApp), List.of("windows"), null, null, null));

        assertThat(view.requestedCount()).isEqualTo(2);
        assertThat(view.acceptedCount()).isEqualTo(2);
        assertThat(view.omittedCount()).isZero();
        ArgumentCaptor<DownloadJob> job = ArgumentCaptor.forClass(DownloadJob.class);
        verify(events).jobRequested(job.capture());
        assertThat(job.getValue().items())
                .filteredOn(item -> item.appId().equals(manualApp))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.sourceRef()).isNull();
                    assertThat(item.appName()).isEqualTo("Aplicación manual");
                    assertThat(item.officialPageUrl()).isEqualTo("https://example.com/manual");
                });
    }

    /** Comprueba que una descarga individual conserve la versión elegida. */
    @Test
    void createsJobWithTheExplicitlySelectedSource() {
        UUID appId = UUID.randomUUID();
        UUID sourceRef = UUID.randomUUID();
        when(sources.findVerifiedSource(appId, sourceRef, List.of("windows")))
                .thenReturn(Optional.of(new CatalogSourceLookup.VerifiedSource(
                        appId,
                        sourceRef,
                        "windows",
                        "x86_64",
                        "Aplicación versionada",
                        "https://example.com/app")));

        DownloadJobView view = service.create(new RequestOwner(null, "browser-hash", "ip-hash"), new DownloadSelection(List.of(appId), List.of("windows"), sourceRef, null, null));

        assertThat(view.acceptedCount()).isOne();
        ArgumentCaptor<DownloadJob> job = ArgumentCaptor.forClass(DownloadJob.class);
        verify(events).jobRequested(job.capture());
        assertThat(job.getValue().items().getFirst().sourceRef()).isEqualTo(sourceRef);
        verify(sources, never()).findVerifiedSources(any(), any());
    }

    /** Comprueba que no pueda reutilizarse una fuente ajena o invalidada. */
    @Test
    void rejectsAnUnavailableExplicitSource() {
        UUID appId = UUID.randomUUID();
        UUID sourceRef = UUID.randomUUID();
        RequestOwner owner = new RequestOwner(null, "browser-hash", "ip-hash");
        DownloadSelection selection = new DownloadSelection(
                List.of(appId), List.of("windows"), sourceRef, null, null);
        when(sources.findVerifiedSource(appId, sourceRef, List.of("windows")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(owner, selection))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("versión seleccionada");

        verify(jobs, never()).save(any(DownloadJob.class));
    }

    /** Comprueba que una fuente concreta no pueda aplicarse a varias aplicaciones. */
    @Test
    void rejectsAnExplicitSourceForMultipleApplications() {
        List<UUID> appIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        DownloadSelection selection = new DownloadSelection(appIds, List.of("windows"), UUID.randomUUID(), null, null);
        RequestOwner owner = new RequestOwner(null, "browser-hash", "ip-hash");

        assertThatThrownBy(() -> service.create(owner, selection))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("única aplicación");

        verify(sources, never()).findVerifiedSources(any(), any());
    }

    @Test
    void previewsAutomaticManualAndUnavailableDependenciesWithoutCreatingAJob() {
        UUID requested = UUID.randomUUID();
        UUID manualDependency = UUID.randomUUID();
        UUID unavailableDependency = UUID.randomUUID();
        UUID sourceRef = UUID.randomUUID();
        List<UUID> expanded = List.of(requested, manualDependency, unavailableDependency);
        when(sources.expandLinuxDependencies(any())).thenReturn(expanded);
        when(sources.findLinuxSources(eq(expanded), any(LinuxTarget.class), isNull()))
                .thenReturn(Map.of(requested, new CatalogSourceLookup.VerifiedSource(
                        requested,
                        sourceRef,
                        "linux",
                        "x86_64",
                        "Aplicación",
                        "https://example.com/app",
                        "automatic")));
        when(sources.findManualSources(expanded)).thenReturn(Map.of(
                manualDependency,
                new CatalogSourceLookup.ManualSource(
                        manualDependency, "Dependencia manual", "https://example.com/dependency")));

        DownloadJobService.LinuxPreview preview = service.previewLinux(new DownloadSelection(List.of(requested), List.of("linux"), null, "apt", "x86_64"));

        assertThat(preview.totalCount()).isEqualTo(3);
        assertThat(preview.automaticCount()).isOne();
        assertThat(preview.manualCount()).isOne();
        assertThat(preview.omittedCount()).isOne();
        assertThat(preview.items())
                .extracting(DownloadJobService.LinuxPreviewItem::dependency)
                .containsExactly(false, true, true);
        verify(jobs, never()).save(any(DownloadJob.class));
        verify(jobs, never()).lockAdmission();
    }

    @Test
    void createsALinuxJobWithExpandedDependenciesAndPersistsItsTarget() {
        UUID requested = UUID.randomUUID();
        UUID dependency = UUID.randomUUID();
        UUID requestedSource = UUID.randomUUID();
        UUID dependencySource = UUID.randomUUID();
        List<UUID> expanded = List.of(requested, dependency);
        when(sources.expandLinuxDependencies(any())).thenReturn(expanded);
        when(sources.findLinuxSources(any(), any(LinuxTarget.class), isNull()))
                .thenReturn(Map.of(
                        requested, new CatalogSourceLookup.VerifiedSource(
                                requested, requestedSource, "linux", "aarch64", "Aplicación", null,
                                "automatic"),
                        dependency, new CatalogSourceLookup.VerifiedSource(
                                dependency, dependencySource, "linux", "aarch64", "Dependencia", null,
                                "automatic")));

        DownloadJobView view = service.create(new RequestOwner(null, "browser-hash", "ip-hash"), new DownloadSelection(List.of(requested), List.of("linux"), null, "apt", "aarch64"));

        assertThat(view.acceptedCount()).isEqualTo(2);
        assertThat(view.linux().target()).isEqualTo("apt");
        assertThat(view.linux().architecture()).isEqualTo("aarch64");
        assertThat(view.linux().addedDependencyAppIds()).containsExactly(dependency);
        verify(jobs).saveLinuxContext(view.id(), view.linux());
        verify(events).jobRequested(any(DownloadJob.class));
    }

    /**
     * Comprueba el escenario {@code rejectsAnonymousCreationWhenItsActiveJobQuotaIsExhausted}.
     */
    @Test
    void rejectsAnonymousCreationWhenItsActiveJobQuotaIsExhausted() {
        when(jobs.countAnonymousNonTerminal("browser-hash")).thenReturn(2L);
        UUID appId = UUID.randomUUID();
        DownloadSelection selection = new DownloadSelection(List.of(appId), List.of(), null, null, null);
        RequestOwner owner = new RequestOwner(null, "browser-hash", "ip-hash");

        assertThatThrownBy(() -> service.create(owner, selection))
                .isInstanceOf(RateLimitException.class)
                .hasMessageContaining("m\u00e1ximo");

        verify(sources, never()).findVerifiedSources(any(), any());
    }

    @Test
    void rejectsTheFiftyFirstPendingJobAfterEightActiveAndFortyTwoQueued() {
        when(jobs.countNonTerminal()).thenReturn(50L);

        assertThatThrownBy(() -> service.create(new RequestOwner(null, "browser-hash", "ip-hash"), new DownloadSelection(List.of(UUID.randomUUID()), List.of("windows"), null, null, null)))
                .isInstanceOfSatisfying(ServiceUnavailableException.class, exception -> {
                    assertThat(exception.code()).isEqualTo("service_busy");
                    assertThat(exception.retryAfterSeconds()).isEqualTo(30);
                });

        verify(jobs).lockAdmission();
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
                NOW,
                NOW.plusSeconds(3600));
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));
        TransactionSynchronizationManager.initSynchronization();
        try {
            handler.applyReady(
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
                NOW.minusSeconds(3600),
                NOW.minusSeconds(1));
        ready.markReady(DownloadJobStatus.READY, "jobs/example/bundle.zip", NOW.minusSeconds(1), NOW.minusSeconds(1));
        when(jobs.findDownloadableExpiredBefore(NOW)).thenReturn(List.of(ready));
        doThrow(new IllegalStateException("minio unavailable"))
                .doThrow(new IllegalStateException("minio unavailable"))
                .doNothing()
                .when(artifacts).deleteJobArtifacts(ready.id());

        expiration.expireReadyJobs();

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
                NOW,
                NOW.plusSeconds(3600));
        UUID firstId = job.items().get(0).id();
        UUID secondId = job.items().get(1).id();
        when(jobs.findById(job.id())).thenReturn(Optional.of(job));

        assertThat(access.itemMetadata(job.id(), List.of(secondId, firstId)))
                .extracting(DownloadJobAccessService.DownloadItemMetadata::appName)
                .containsExactly("Segunda", "Primera");

        UUID foreignItem = UUID.randomUUID();
        UUID jobId = job.id();
        List<UUID> requestedItems = List.of(firstId, foreignItem);
        assertThatThrownBy(() -> access.itemMetadata(jobId, requestedItems))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("No existe el trabajo.");
    }
}
