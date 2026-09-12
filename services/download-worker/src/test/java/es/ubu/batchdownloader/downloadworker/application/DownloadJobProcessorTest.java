package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobProgressedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobReadyEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.InstallationMetadata;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.infrastructure.Hashing;
import es.ubu.batchdownloader.downloadworker.infrastructure.archive.ZipArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Caracteriza resultados parciales y manuales, orden real de finalización, espera de empaquetado,
 * limpieza y contenido Linux usando transferencias y almacenamiento controlados.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class DownloadJobProcessorTest {
    /**
     * Valor compartido que fija g o o d  i t e m  i d para el comportamiento del componente.
     */
    private static final UUID GOOD_ITEM_ID = id("item-ok");
    /**
     * Valor compartido que fija b a d  i t e m  i d para el comportamiento del componente.
     */
    private static final UUID BAD_ITEM_ID = id("item-bad");
    /**
     * Valor compartido que fija f a s t  i t e m  i d para el comportamiento del componente.
     */
    private static final UUID FAST_ITEM_ID = id("item-fast");
    /**
     * Valor compartido que fija s l o w  i t e m  i d para el comportamiento del componente.
     */
    private static final UUID SLOW_ITEM_ID = id("item-slow");
    /**
     * Dato compartido {@code temp} para los escenarios de prueba.
     */
    @TempDir
    Path temp;

    /**
     * Dato compartido {@code executor} para los escenarios de prueba.
     */
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /**
     * Interrumpe el pool de la prueba al terminar cada escenario para no conservar tareas entre
     * casos.
     */
    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    /**
     * Con un instalador válido y otro rechazado, comprueba ZIP y manifiesto parcial, acceso manual,
     * seis eventos de progreso, recuentos y limpieza de temporales; no se publican objetos
     * individuales de instalador.
     */
    @Test
    void createsPartialManifestZipStoresArtifactsAndPublishesDeterministicEvents() throws Exception {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RemoteDownloader downloader = (item, filename, target, budget, maxFileBytes) -> {
            if (item.itemId().equals(BAD_ITEM_ID)) {
                throw new DownloadRejectedException("remote_http_404");
            }
            try {
                byte[] content = ("content-" + item.itemId()).getBytes();
                budget.consume(content.length);
                Files.createDirectories(target.getParent());
                Files.write(target, content);
                return new DownloadedArtifact(
                        item.itemId(), item.appId(), item.sourceRef(), filename, target,
                        content.length, Hashing.sha256(target), null);
            } catch (DownloadRejectedException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new InfrastructureException("test_write_failed", exception);
            }
        };
        DownloadJobProcessor processor = processor(downloader, store, publisher, 10);
        DownloadJobRequestedEvent event = event(List.of(
                item("ok"),
                item("bad")));

        processor.process(event);

        assertThat(store.objects.keySet()).contains(
                "jobs/" + event.payload().jobId() + "/manifest.json",
                "jobs/" + event.payload().jobId() + "/bundle.zip");
        assertThat(store.objects.keySet())
                .doesNotContain("jobs/" + event.payload().jobId() + "/files/Good.exe");
        String manifest = new String(store.objects.get("jobs/" + event.payload().jobId() + "/manifest.json"));
        assertThat(manifest).contains("\"manifestVersion\" : 3")
                .contains("\"status\" : \"PARTIAL\"")
                .contains("remote_http_404")
                .contains("\"appName\" : \"Bad app\"")
                .contains("\"archivePath\" : \"Good.exe\"")
                .contains("\"objectKey\" : null")
                .contains("\"manualShortcut\" : \"Descargas manuales/Bad app.url\"")
                .contains("Good.exe");
        assertThat(zipEntry(
                        store.objects.get("jobs/" + event.payload().jobId() + "/bundle.zip"),
                        "Descargas manuales/Bad app.url"))
                .isEqualTo("[InternetShortcut]\r\nURL=https://vendor.example/apps/bad\r\n");
        assertThat(publisher.routingKeys)
                .containsOnly(EventTypes.JOB_PROGRESSED_ROUTING_KEY, EventTypes.JOB_READY_ROUTING_KEY);
        assertThat(publisher.routingKeys)
                .filteredOn(EventTypes.JOB_PROGRESSED_ROUTING_KEY::equals)
                .hasSize(6);
        DownloadJobReadyEvent readyEvent = (DownloadJobReadyEvent) publisher.events.getLast();
        assertThat(readyEvent.payload().status()).isEqualTo("PARTIAL");
        assertThat(readyEvent.payload().successfulItems()).isEqualTo(1);
        assertThat(readyEvent.payload().failedItems()).isEqualTo(1);
        try (var children = Files.list(temp)) {
            assertThat(children).isEmpty();
        }
    }

    /**
     * Supera el máximo de elementos y comprueba que se publica únicamente el fallo terminal sin
     * llamar al descargador ni guardar objetos.
     */
    @Test
    void rejectsOversizedJobAsAStableFailureEventWithoutDownloading() {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RemoteDownloader unused = (item, filename, target, budget, maxFileBytes) -> {
            throw new AssertionError("downloader must not be invoked");
        };
        DownloadJobProcessor processor = processor(unused, store, publisher, 1);

        processor.process(event(List.of(item("one"), item("two"))));

        assertThat(store.objects).isEmpty();
        assertThat(publisher.routingKeys).containsExactly(EventTypes.JOB_FAILED_ROUTING_KEY);
    }

    /**
     * Rechaza todos los instaladores y omite páginas oficiales; comprueba que no se guarda un ZIP y
     * que el último evento es de fallo.
     */
    @Test
    void doesNotPublishAnUnusableArchiveWhenEveryInstallerIsRejected() {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RemoteDownloader rejected = (item, filename, target, budget, maxFileBytes) -> {
            throw new DownloadRejectedException("remote_http_404");
        };
        DownloadJobProcessor processor = processor(
                rejected, store, publisher, 10, metadataLookup(false));

        processor.process(event(List.of(item("bad"))));

        assertThat(store.objects).isEmpty();
        assertThat(publisher.routingKeys).containsExactly(
                EventTypes.JOB_PROGRESSED_ROUTING_KEY,
                EventTypes.JOB_PROGRESSED_ROUTING_KEY,
                EventTypes.JOB_PROGRESSED_ROUTING_KEY,
                EventTypes.JOB_FAILED_ROUTING_KEY);
    }

    /**
     * Rechaza el instalador pero conserva una página oficial válida y comprueba un resultado
     * MANUAL_ONLY con cero éxitos y un elemento fallido.
     */
    @Test
    void createsManualOnlyZipWhenEveryFailedAppHasASafeOfficialPage() {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RemoteDownloader rejected = (item, filename, target, budget, maxFileBytes) -> {
            throw new DownloadRejectedException("remote_http_404");
        };
        DownloadJobProcessor processor = processor(rejected, store, publisher, 10);
        DownloadJobRequestedEvent event = event(List.of(item("bad")));

        processor.process(event);

        String manifest = new String(store.objects.get(
                "jobs/" + event.payload().jobId() + "/manifest.json"));
        assertThat(manifest).contains("\"status\" : \"MANUAL_ONLY\"");
        DownloadJobReadyEvent ready = (DownloadJobReadyEvent) publisher.events.getLast();
        assertThat(ready.payload().status()).isEqualTo("MANUAL_ONLY");
        assertThat(ready.payload().successfulItems()).isZero();
        assertThat(ready.payload().failedItems()).isEqualTo(1);
    }

    /**
     * Procesa un elemento sin sourceRef y comprueba el acceso .url y el resultado MANUAL_ONLY sin
     * efectuar una transferencia.
     */
    @Test
    void createsShortcutForManualItemWithoutResolvingOrDownloading() throws Exception {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RemoteDownloader unused = (item, filename, target, budget, maxFileBytes) -> {
            throw new AssertionError("manual item must not be downloaded");
        };
        DownloadJobProcessor processor = processor(unused, store, publisher, 10);
        DownloadItemRequest manual = new DownloadItemRequest(
                BAD_ITEM_ID,
                id("app-manual"),
                null);
        DownloadJobRequestedEvent event = event(List.of(manual));

        processor.process(event);

        byte[] archive = store.objects.get("jobs/" + event.payload().jobId() + "/bundle.zip");
        assertThat(zipEntry(archive, "Descargas manuales/Bad app.url"))
                .isEqualTo("[InternetShortcut]\r\nURL=https://vendor.example/apps/bad\r\n");
        DownloadJobReadyEvent ready = (DownloadJobReadyEvent) publisher.events.getLast();
        assertThat(ready.payload().status()).isEqualTo("MANUAL_ONLY");
    }

    /**
     * Aporta una página con access_token tras rechazar el instalador y comprueba que no se guarda
     * ningún objeto ni se publica disponibilidad.
     */
    @Test
    void rejectsSensitiveOfficialPageQueriesInsteadOfWritingThemToAShortcut() {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RemoteDownloader rejected = (item, filename, target, budget, maxFileBytes) -> {
            throw new DownloadRejectedException("remote_http_404");
        };
        JobItemMetadataLookup sensitiveMetadata = (jobId, items) -> Map.of(
                BAD_ITEM_ID,
                new DownloadItemMetadata(
                        BAD_ITEM_ID,
                        items.getFirst().appId(),
                        "Bad app",
                        "https://vendor.example/app?access_token=secret"));
        DownloadJobProcessor processor = processor(
                rejected, store, publisher, 10, sensitiveMetadata);

        processor.process(event(List.of(item("bad"))));

        assertThat(store.objects).isEmpty();
        assertThat(publisher.routingKeys.getLast()).isEqualTo(EventTypes.JOB_FAILED_ROUTING_KEY);
    }

    /**
     * Bloquea el elemento lento hasta publicarse el rápido y comprueba que los eventos COMPLETED
     * siguen ese orden aunque la solicitud enumere primero el lento.
     */
    @Test
    void publishesTerminalItemsInTheirRealCompletionOrder() {
        CountDownLatch fastTerminalPublished = new CountDownLatch(1);
        RecordingPublisher publisher = new RecordingPublisher(fastTerminalPublished);
        MemoryArtifactStore store = new MemoryArtifactStore();
        RemoteDownloader downloader = (item, filename, target, budget, maxFileBytes) -> {
            try {
                if (item.itemId().equals(SLOW_ITEM_ID)
                        && !fastTerminalPublished.await(5, TimeUnit.SECONDS)) {
                    throw new InfrastructureException(
                            "test_timeout", new IllegalStateException("Fast item did not finish"));
                }
                byte[] content = item.itemId().toString().getBytes();
                budget.consume(content.length);
                Files.createDirectories(target.getParent());
                Files.write(target, content);
                return new DownloadedArtifact(
                        item.itemId(), item.appId(), item.sourceRef(), filename, target,
                        content.length, Hashing.sha256(target), null);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new InfrastructureException("test_interrupted", exception);
            } catch (DownloadRejectedException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new InfrastructureException("test_write_failed", exception);
            }
        };
        DownloadJobProcessor processor = processor(downloader, store, publisher, 10);

        processor.process(event(List.of(item("slow"), item("fast"))));

        assertThat(publisher.events.stream()
                        .filter(DownloadJobProgressedEvent.class::isInstance)
                        .map(DownloadJobProgressedEvent.class::cast)
                        .filter(progress -> "COMPLETED".equals(progress.payload().status()))
                        .map(progress -> progress.payload().itemId()))
                .containsExactly(FAST_ITEM_ID, SLOW_ITEM_ID);
    }

    /**
     * Retiene todos los permisos de ZIP y comprueba que la descarga ya publicó COMPLETED mientras
     * el procesamiento sigue pendiente y todavía no hay objetos; al liberar permiso se guarda el
     * ZIP.
     */
    @Test
    void finishesDownloadsBeforeWaitingForAPackagingPermit() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        RecordingPublisher publisher = new RecordingPublisher(completed);
        MemoryArtifactStore store = new MemoryArtifactStore();
        RemoteDownloader downloader = (item, filename, target, budget, maximum) -> {
            try {
                byte[] content = "complete-before-packaging".getBytes();
                budget.consume(content.length);
                Files.createDirectories(target.getParent());
                Files.write(target, content);
                return new DownloadedArtifact(
                        item.itemId(), item.appId(), item.sourceRef(), filename, target,
                        content.length, Hashing.sha256(target), null);
            } catch (Exception exception) {
                throw new InfrastructureException("test_write_failed", exception);
            }
        };
        Semaphore packaging = new Semaphore(0, true);
        DownloadJobProcessor processor = processor(
                downloader, store, publisher, 10, metadataLookup(true), packaging);

        CompletableFuture<Void> processing = CompletableFuture.runAsync(
                () -> processor.process(event(List.of(item("fast")))));

        assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(processing).isNotDone();
        assertThat(store.objects).isEmpty();
        packaging.release();
        processing.get(2, TimeUnit.SECONDS);
        assertThat(store.objects.keySet()).anyMatch(key -> key.endsWith("/bundle.zip"));
    }

    /**
     * Procesa un AppImage y comprueba launcher, receta, referencias de checksums y soporte
     * automático en manifiesto, sin exponer el host de descarga en la configuración.
     */
    @Test
    void addsTheOfflineInstallerToLinuxArchives() throws Exception {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        InstallationMetadata installation = new InstallationMetadata(
                "Example Linux", "1.0", ".appimage", "linux", "x86_64", null, null);
        SourceReferenceResolver resolver = linuxResolver(installation);
        RemoteDownloader downloader = localDownloader();
        DownloadJobProcessor processor = processor(
                downloader, store, publisher, 10, metadataLookup(true),
                new Semaphore(1, true), resolver);
        DownloadJobRequestedEvent event = event(List.of(item("ok")));

        processor.process(event);

        byte[] archive = store.objects.get("jobs/" + event.payload().jobId() + "/bundle.zip");
        assertThat(zipEntry(archive, "install.sh")).startsWith("#!/usr/bin/env bash");
        assertThat(zipEntry(archive, "config/components/" + id("app-ok") + ".json"))
                .contains("\"strategy\" : \"appimage\"")
                .doesNotContain("downloads.example.com");
        assertThat(zipEntry(archive, "checksums.sha256"))
                .contains("  Example.AppImage\n")
                .contains("  manifest.json\n");
        String manifest = new String(store.objects.get(
                "jobs/" + event.payload().jobId() + "/manifest.json"));
        assertThat(manifest)
                .contains("\"operatingSystem\" : \"linux\"")
                .contains("\"installationSupport\" : \"automatic\"");
    }

    /**
     * Desactiva el runtime Linux y comprueba que desaparece install.sh pero se conservan instalador
     * y manifiesto versionado dentro del ZIP.
     */
    @Test
    void featureFlagOmitsOnlyTheLinuxInstallerRuntime() throws Exception {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        InstallationMetadata installation = new InstallationMetadata(
                "Example Linux", "1.0", ".appimage", "linux", "x86_64", null, null);
        DownloadJobProcessor processor = processor(
                localDownloader(), store, publisher, 10, metadataLookup(true),
                new Semaphore(1, true), linuxResolver(installation));
        ReflectionTestUtils.setField(processor, "linuxInstallerEnabled", false);
        DownloadJobRequestedEvent event = event(List.of(item("ok")));

        processor.process(event);

        byte[] archive = store.objects.get("jobs/" + event.payload().jobId() + "/bundle.zip");
        assertThat(zipEntry(archive, "install.sh")).isNull();
        assertThat(zipEntry(archive, "Example.AppImage")).isEqualTo("linux-payload");
        assertThat(zipEntry(archive, "manifest.json")).contains("\"manifestVersion\" : 3");
    }

    /**
     * Compone el procesador con metadatos de páginas oficiales seguras para los escenarios
     * generales.
     *
     * @param downloader Doble de transferencia que controla contenidos o fallos del escenario.
     * @param store Almacén de prueba que conserva los objetos publicados para sus aserciones.
     * @param publisher Registro de eventos que permite comprobar orden y resultados terminales.
     * @param maxItems Límite de elementos configurado para probar admisión o rechazo del trabajo.
     * @return procesador aislado con los dobles recibidos.
     */
    private DownloadJobProcessor processor(
            RemoteDownloader downloader,
            ArtifactStore store,
            EventPublisher publisher,
            int maxItems) {
        return processor(downloader, store, publisher, maxItems, metadataLookup(true));
    }

    /**
     * Añade un permiso justo de empaquetado al procesador con metadatos controlados.
     *
     * @param downloader Doble de transferencia que controla contenidos o fallos del escenario.
     * @param store Almacén de prueba que conserva los objetos publicados para sus aserciones.
     * @param publisher Registro de eventos que permite comprobar orden y resultados terminales.
     * @param maxItems Límite de elementos configurado para probar admisión o rechazo del trabajo.
     * @param metadataLookup Consulta simulada de nombres y páginas oficiales para alternativas
     *     manuales.
     * @return procesador con una plaza de ZIP disponible.
     */
    private DownloadJobProcessor processor(
            RemoteDownloader downloader,
            ArtifactStore store,
            EventPublisher publisher,
            int maxItems,
            JobItemMetadataLookup metadataLookup) {
        return processor(
                downloader, store, publisher, maxItems, metadataLookup,
                new java.util.concurrent.Semaphore(1, true));
    }

    /**
     * Añade una resolución Windows determinista a la composición con permisos controlados.
     *
     * @param downloader Doble de transferencia que controla contenidos o fallos del escenario.
     * @param store Almacén de prueba que conserva los objetos publicados para sus aserciones.
     * @param publisher Registro de eventos que permite comprobar orden y resultados terminales.
     * @param maxItems Límite de elementos configurado para probar admisión o rechazo del trabajo.
     * @param metadataLookup Consulta simulada de nombres y páginas oficiales para alternativas
     *     manuales.
     * @param packagingSemaphore Permisos controlados por la prueba para observar la espera antes de
     *     comprimir.
     * @return procesador con fuentes y tamaños conocidos de prueba.
     */
    private DownloadJobProcessor processor(
            RemoteDownloader downloader,
            ArtifactStore store,
            EventPublisher publisher,
            int maxItems,
            JobItemMetadataLookup metadataLookup,
            Semaphore packagingSemaphore) {
        SourceReferenceResolver resolver = item -> new ResolvedDownloadItem(
                item.itemId(),
                item.appId(),
                item.sourceRef(),
                URI.create("https://downloads.example.com/" + filename(item.itemId())),
                filename(item.itemId()),
                "windows",
                "x86_64",
                1_024L,
                null,
                null);
        return processor(
                downloader, store, publisher, maxItems, metadataLookup,
                packagingSemaphore, resolver);
    }

    /**
     * Compone las mismas fases del procesador con reloj fijo, directorio temporal y adaptadores
     * controlados sin depender de Core, scraper o MinIO.
     *
     * @param downloader Doble de transferencia que controla contenidos o fallos del escenario.
     * @param store Almacén de prueba que conserva los objetos publicados para sus aserciones.
     * @param publisher Registro de eventos que permite comprobar orden y resultados terminales.
     * @param maxItems Límite de elementos configurado para probar admisión o rechazo del trabajo.
     * @param metadataLookup Consulta simulada de nombres y páginas oficiales para alternativas
     *     manuales.
     * @param packagingSemaphore Permisos controlados por la prueba para observar la espera antes de
     *     comprimir.
     * @param resolver Resolución simulada que fija URI y metadatos de instalación del escenario.
     * @return procesador que permite verificar contenido y coordinación localmente.
     */
    private DownloadJobProcessor processor(
            RemoteDownloader downloader,
            ArtifactStore store,
            EventPublisher publisher,
            int maxItems,
            JobItemMetadataLookup metadataLookup,
            Semaphore packagingSemaphore,
            SourceReferenceResolver resolver) {
        DownloadProperties downloadProperties = new DownloadProperties(
                maxItems,
                DataSize.ofMegabytes(10),
                DataSize.ofMegabytes(20),
                3,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                2,
                Duration.ofMinutes(5),
                temp.toString());
        StorageProperties storage = new StorageProperties(
                "http://minio", "key", "secret", "installers", Duration.ofHours(1));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC);
        DownloadCancellationRegistry cancellations = new DownloadCancellationRegistry();
        DownloadWorkerMetrics metrics = new DownloadWorkerMetrics(registry);
        FilenamePolicy filenames = new FilenamePolicy();
        DownloadEventEmitter events = new DownloadEventEmitter(publisher, storage, clock);
        DownloadJobFiles files = new DownloadJobFiles(store, metrics, downloadProperties);
        DownloadPipelineFactory pipelines = (event, items, directory, window) -> new DownloadPipeline(
                event, items, directory, window, executor, downloader, filenames, downloadProperties,
                cancellations, metrics, events, clock, files);
        return new DownloadJobProcessor(pipelines, store, new ZipArchiveBuilder(), downloadProperties, clock,
                cancellations, new JobCapacity(downloadProperties.jobConcurrency(), registry), packagingSemaphore,
                metrics, new TemporaryDiskCapacity(downloadProperties), null, events, files,
                new ManualShortcutWriter(metadataLookup, filenames,
                        new PublicHttpsUriPolicy(hostname -> List.of(publicAddress()))),
                new DownloadManifestWriter(mapper, clock), new LinuxInstallerBundleWriter(mapper),
                new DownloadResolutionService(resolver, executor, downloadProperties, cancellations, events, clock));
    }

    /**
     * Construye una resolución AppImage que conserva los UUID recibidos y los metadatos del
     * escenario.
     *
     * @param installation Metadatos Linux que se conservan al resolver el instalador de ejemplo.
     * @return resolutor local sin llamadas HTTP.
     */
    private SourceReferenceResolver linuxResolver(InstallationMetadata installation) {
        return item -> new ResolvedDownloadItem(
                item.itemId(),
                item.appId(),
                item.sourceRef(),
                URI.create("https://downloads.example.com/Example.AppImage"),
                "Example.AppImage",
                "linux",
                "x86_64",
                1_024L,
                null,
                "application/octet-stream",
                installation);
    }

    /**
     * Simula una transferencia escribiendo linux-payload, consumiendo presupuesto y calculando la
     * huella real del temporal.
     *
     * @return descargador local que conserva los metadatos de instalación.
     */
    private RemoteDownloader localDownloader() {
        return (item, filename, target, budget, maximum) -> {
            try {
                byte[] content = "linux-payload".getBytes();
                budget.consume(content.length);
                Files.createDirectories(target.getParent());
                Files.write(target, content);
                return new DownloadedArtifact(
                        item.itemId(), item.appId(), item.sourceRef(), filename, target,
                        content.length, Hashing.sha256(target), null, item.installation());
            } catch (Exception exception) {
                throw new InfrastructureException("test_write_failed", exception);
            }
        };
    }

    /**
     * Asigna nombres estables a los elementos y permite activar o retirar su página oficial.
     *
     * @param safeOfficialPage true proporciona una página HTTPS pública; false deja al elemento sin
     *     alternativa manual.
     * @return consulta de metadatos en memoria por UUID de elemento.
     */
    private JobItemMetadataLookup metadataLookup(boolean safeOfficialPage) {
        return (jobId, items) -> items.stream().collect(java.util.stream.Collectors.toMap(
                DownloadItemRequest::itemId,
                item -> new DownloadItemMetadata(
                        item.itemId(),
                        item.appId(),
                        item.itemId().equals(BAD_ITEM_ID) ? "Bad app" : "Example app",
                        safeOfficialPage
                                ? "https://vendor.example/apps/"
                                        + (item.itemId().equals(BAD_ITEM_ID) ? "bad" : "example")
                                : null)));
    }

    /**
     * Aporta una dirección pública fija al doble DNS para evitar consultas de red durante las
     * pruebas.
     *
     * @return dirección IPv4 8.8.8.8 construida sin consultar DNS.
     */
    private static InetAddress publicAddress() {
        try {
            return InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        } catch (java.net.UnknownHostException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Recorre el ZIP en memoria y lee la primera entrada cuyo nombre coincide exactamente con la
     * ruta solicitada.
     *
     * @param archive Bytes del ZIP producido por el procesador.
     * @param expectedPath Nombre exacto de la entrada cuyo contenido debe inspeccionarse.
     * @return contenido textual de la entrada o null si no existe.
     * @throws java.lang.Exception si no puede leerse el ZIP generado por el escenario.
     */
    private static String zipEntry(byte[] archive, String expectedPath) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (expectedPath.equals(entry.getName())) {
                    return new String(zip.readAllBytes());
                }
            }
        }
        return null;
    }

    /**
     * Crea una solicitud válida con fecha fija y UUID de evento, trabajo y correlación propios de
     * la prueba.
     *
     * @param items Selección ordenada que se incluye en la solicitud de prueba.
     * @return sobre de solicitud con la selección recibida.
     */
    private DownloadJobRequestedEvent event(List<DownloadItemRequest> items) {
        return new DownloadJobRequestedEvent(
                UUID.randomUUID(),
                EventTypes.JOB_REQUESTED,
                EventTypes.CURRENT_VERSION,
                Instant.parse("2026-07-11T11:00:00Z"),
                UUID.randomUUID().toString(),
                null,
                new DownloadJobPayload(
                        UUID.randomUUID(),
                        items));
    }

    /**
     * Deriva UUID diferentes de elemento, aplicación y fuente a partir de una semilla común.
     *
     * @param id Texto de fixture del que se derivan UUID estables de elemento, aplicación y fuente.
     * @return selección exacta reproducible para el escenario.
     */
    private DownloadItemRequest item(String id) {
        return new DownloadItemRequest(
                DownloadJobProcessorTest.id("item-" + id),
                DownloadJobProcessorTest.id("app-" + id),
                DownloadJobProcessorTest.id("source-" + id));
    }

    /**
     * Distingue el instalador rechazado del resto al construir respuestas del resolutor de prueba.
     *
     * @param itemId UUID del elemento que distingue el nombre Bad.exe del nombre Good.exe.
     * @return Bad.exe para BAD_ITEM_ID y Good.exe en los demás casos.
     */
    private static String filename(UUID itemId) {
        return BAD_ITEM_ID.equals(itemId) ? "Bad.exe" : "Good.exe";
    }

    /**
     * Deriva un UUID de nombre a partir de una semilla UTF-8 de fixture.
     *
     * @param value Semilla UTF-8 que identifica de forma reproducible una entidad del escenario.
     * @return UUID reproducible para la misma semilla.
     */
    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Captura objetos completos en memoria para comprobar contenido y compensación sin un servidor
     * de almacenamiento.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Pruebas de procesamiento y capacidad
     */
    private static class MemoryArtifactStore implements ArtifactStore {
        /**
         * Dato compartido {@code objects} para los escenarios de prueba.
         */
        private final Map<String, byte[]> objects = new HashMap<>();

        /**
         * Lee el archivo producido por el respaldo de streaming y conserva sus bytes bajo la clave
         * solicitada.
         *
         * @param objectKey Clave con la que se conserva o retira un objeto del almacén en memoria.
         * @param source Archivo local cuyos bytes se capturan para inspeccionar el resultado.
         * @param contentType Metadato del puerto que el doble en memoria no necesita conservar.
         */
        @Override
        public void put(String objectKey, Path source, String contentType) {
            try {
                objects.put(objectKey, Files.readAllBytes(source));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }

        /**
         * Retira el objeto capturado para que las aserciones puedan comprobar compensación de
         * resultados incompletos.
         *
         * @param objectKey Clave con la que se conserva o retira un objeto del almacén en memoria.
         */
        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }

    }

    /**
     * Captura claves y sobres de eventos y permite sincronizar descargas con la publicación del
     * elemento rápido.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Pruebas de procesamiento y capacidad
     */
    private static class RecordingPublisher implements EventPublisher {
        /**
         * Dato compartido {@code routingKeys} para los escenarios de prueba.
         */
        private final List<String> routingKeys = new ArrayList<>();
        /**
         * Dato compartido {@code events} para los escenarios de prueba.
         */
        private final List<Object> events = new ArrayList<>();
        /**
         * Dato compartido {@code fastTerminalPublished} para los escenarios de prueba.
         */
        private final CountDownLatch fastTerminalPublished;

        /**
         * Crea un registro de eventos sin barrera adicional de sincronización.
         */
        private RecordingPublisher() {
            this(null);
        }

        /**
         * Conecta una barrera opcional que permite comprobar el orden real de finalización.
         *
         * @param fastTerminalPublished Barrera opcional que se abre cuando el elemento rápido
         *     publica COMPLETED.
         */
        private RecordingPublisher(CountDownLatch fastTerminalPublished) {
            this.fastTerminalPublished = fastTerminalPublished;
        }

        /**
         * Registra el mensaje y abre la barrera cuando se publica COMPLETED para el elemento
         * rápido.
         *
         * @param routingKey Clave registrada para comprobar qué contrato de evento se publicó.
         * @param event Sobre registrado para comprobar estado, recuentos y orden de publicación.
         */
        @Override
        public void publish(String routingKey, Object event) {
            routingKeys.add(routingKey);
            events.add(event);
            if (fastTerminalPublished != null
                    && event instanceof DownloadJobProgressedEvent progressed
                    && FAST_ITEM_ID.equals(progressed.payload().itemId())
                    && "COMPLETED".equals(progressed.payload().status())) {
                fastTerminalPublished.countDown();
            }
        }
    }
}
