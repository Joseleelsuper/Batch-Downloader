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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * Agrupa los escenarios de prueba de {@code DownloadJobProcessorTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class DownloadJobProcessorTest {
    /**
     * Constante que define {@code GOOD_ITEM_ID}.
     */
    private static final UUID GOOD_ITEM_ID = id("item-ok");
    /**
     * Constante que define {@code BAD_ITEM_ID}.
     */
    private static final UUID BAD_ITEM_ID = id("item-bad");
    /**
     * Constante que define {@code FAST_ITEM_ID}.
     */
    private static final UUID FAST_ITEM_ID = id("item-fast");
    /**
     * Constante que define {@code SLOW_ITEM_ID}.
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
     * Libera el estado utilizado por los escenarios de prueba.
     */
    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    /**
     * Comprueba el escenario {@code
     * createsPartialManifestZipStoresArtifactsAndPublishesDeterministicEvents}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
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
                item("ok", "Good.exe"),
                item("bad", "Bad.exe")));

        processor.process(event);

        assertThat(store.objects.keySet()).contains(
                "jobs/" + event.payload().jobId() + "/manifest.json",
                "jobs/" + event.payload().jobId() + "/bundle.zip");
        assertThat(store.objects.keySet())
                .doesNotContain("jobs/" + event.payload().jobId() + "/files/Good.exe");
        String manifest = new String(store.objects.get("jobs/" + event.payload().jobId() + "/manifest.json"));
        assertThat(manifest).contains("\"manifestVersion\" : 2")
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
     * Comprueba el escenario {@code rejectsOversizedJobAsAStableFailureEventWithoutDownloading}.
     */
    @Test
    void rejectsOversizedJobAsAStableFailureEventWithoutDownloading() {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RemoteDownloader unused = (item, filename, target, budget, maxFileBytes) -> {
            throw new AssertionError("downloader must not be invoked");
        };
        DownloadJobProcessor processor = processor(unused, store, publisher, 1);

        processor.process(event(List.of(item("one", "one.exe"), item("two", "two.exe"))));

        assertThat(store.objects).isEmpty();
        assertThat(publisher.routingKeys).containsExactly(EventTypes.JOB_FAILED_ROUTING_KEY);
    }

    /**
     * Comprueba el escenario {@code doesNotPublishAnUnusableArchiveWhenEveryInstallerIsRejected}.
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

        processor.process(event(List.of(item("bad", "Bad.exe"))));

        assertThat(store.objects).isEmpty();
        assertThat(publisher.routingKeys).containsExactly(
                EventTypes.JOB_PROGRESSED_ROUTING_KEY,
                EventTypes.JOB_PROGRESSED_ROUTING_KEY,
                EventTypes.JOB_PROGRESSED_ROUTING_KEY,
                EventTypes.JOB_FAILED_ROUTING_KEY);
    }

    /**
     * Comprueba el escenario {@code createsManualOnlyZipWhenEveryFailedAppHasASafeOfficialPage}.
     */
    @Test
    void createsManualOnlyZipWhenEveryFailedAppHasASafeOfficialPage() {
        MemoryArtifactStore store = new MemoryArtifactStore();
        RecordingPublisher publisher = new RecordingPublisher();
        RemoteDownloader rejected = (item, filename, target, budget, maxFileBytes) -> {
            throw new DownloadRejectedException("remote_http_404");
        };
        DownloadJobProcessor processor = processor(rejected, store, publisher, 10);
        DownloadJobRequestedEvent event = event(List.of(item("bad", "Bad.exe")));

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
     * Comprueba que una aplicación sin fuente llegue directamente al ZIP como acceso manual.
     *
     * @throws Exception Si no puede leerse el ZIP generado.
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
     * Comprueba el escenario {@code
     * rejectsSensitiveOfficialPageQueriesInsteadOfWritingThemToAShortcut}.
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

        processor.process(event(List.of(item("bad", "Bad.exe"))));

        assertThat(store.objects).isEmpty();
        assertThat(publisher.routingKeys.getLast()).isEqualTo(EventTypes.JOB_FAILED_ROUTING_KEY);
    }

    /**
     * Comprueba el escenario {@code publishesTerminalItemsInTheirRealCompletionOrder}.
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

        processor.process(event(List.of(item("slow", "Slow.exe"), item("fast", "Fast.exe"))));

        assertThat(publisher.events.stream()
                        .filter(DownloadJobProgressedEvent.class::isInstance)
                        .map(DownloadJobProgressedEvent.class::cast)
                        .filter(progress -> "COMPLETED".equals(progress.payload().status()))
                        .map(progress -> progress.payload().itemId()))
                .containsExactly(FAST_ITEM_ID, SLOW_ITEM_ID);
    }

    /**
     * Procesa los datos recibidos mediante {@code processor}.
     *
     * @param downloader Valor de {@code downloader} utilizado por la operación.
     * @param store Valor de {@code store} utilizado por la operación.
     * @param publisher Valor de {@code publisher} utilizado por la operación.
     * @param maxItems Valor de {@code maxItems} utilizado por la operación.
     * @return Resultado producido por {@code processor}.
     */
    private DownloadJobProcessor processor(
            RemoteDownloader downloader,
            ArtifactStore store,
            EventPublisher publisher,
            int maxItems) {
        return processor(downloader, store, publisher, maxItems, metadataLookup(true));
    }

    /**
     * Procesa los datos recibidos mediante {@code processor}.
     *
     * @param downloader Valor de {@code downloader} utilizado por la operación.
     * @param store Valor de {@code store} utilizado por la operación.
     * @param publisher Valor de {@code publisher} utilizado por la operación.
     * @param maxItems Valor de {@code maxItems} utilizado por la operación.
     * @param metadataLookup Valor de {@code metadataLookup} utilizado por la operación.
     * @return Resultado producido por {@code processor}.
     */
    private DownloadJobProcessor processor(
            RemoteDownloader downloader,
            ArtifactStore store,
            EventPublisher publisher,
            int maxItems,
            JobItemMetadataLookup metadataLookup) {
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
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        return new DownloadJobProcessor(
                resolver,
                metadataLookup,
                downloader,
                store,
                new ZipArchiveBuilder(),
                publisher,
                new FilenamePolicy(),
                new PublicHttpsUriPolicy(hostname -> List.of(publicAddress())),
                mapper,
                executor,
                downloadProperties,
                storage,
                Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC),
                new DownloadCancellationRegistry(),
                new JobCapacity(downloadProperties.jobConcurrency(), registry),
                new java.util.concurrent.Semaphore(downloadProperties.packagingConcurrency(), true),
                new DownloadWorkerMetrics(registry),
                new TemporaryDiskCapacity(downloadProperties));
    }

    /**
     * Ejecuta la operación {@code metadataLookup}.
     *
     * @param safeOfficialPage Valor de {@code safeOfficialPage} utilizado por la operación.
     * @return Resultado producido por {@code metadataLookup}.
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
     * Ejecuta la operación {@code publicAddress}.
     *
     * @return Resultado producido por {@code publicAddress}.
     * @throws AssertionError Si no puede completarse la operación bajo las condiciones requeridas.
     */
    private static InetAddress publicAddress() {
        try {
            return InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        } catch (java.net.UnknownHostException exception) {
            throw new AssertionError(exception);
        }
    }

    /**
     * Ejecuta la operación {@code zipEntry}.
     *
     * @param archive Valor de {@code archive} utilizado por la operación.
     * @param expectedPath Valor esperado de {@code path}.
     * @return Resultado producido por {@code zipEntry}.
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
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
     * Ejecuta la operación {@code event}.
     *
     * @param items Colección de elementos que debe procesarse.
     * @return Resultado producido por {@code event}.
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
     * Ejecuta la operación {@code item}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @param filename Valor de {@code filename} utilizado por la operación.
     * @return Resultado producido por {@code item}.
     */
    private DownloadItemRequest item(String id, String filename) {
        return new DownloadItemRequest(
                DownloadJobProcessorTest.id("item-" + id),
                DownloadJobProcessorTest.id("app-" + id),
                DownloadJobProcessorTest.id("source-" + id));
    }

    /**
     * Ejecuta la operación {@code filename}.
     *
     * @param itemId Identificador de {@code item} utilizado por la operación.
     * @return Resultado producido por {@code filename}.
     */
    private static String filename(UUID itemId) {
        return BAD_ITEM_ID.equals(itemId) ? "Bad.exe" : "Good.exe";
    }

    /**
     * Ejecuta la operación {@code id}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code id}.
     */
    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Agrupa los escenarios de prueba de {@code MemoryArtifactStore}.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private static class MemoryArtifactStore implements ArtifactStore {
        /**
         * Dato compartido {@code objects} para los escenarios de prueba.
         */
        private final Map<String, byte[]> objects = new HashMap<>();

        /**
         * Implementa {@code put} para {@code MemoryArtifactStore}.
         *
         * @param objectKey Valor de {@code objectKey} utilizado por la operación.
         * @param source Fuente de descarga sobre la que se actúa.
         * @param contentType Valor de {@code contentType} utilizado por la operación.
         * @throws RuntimeException Si no puede completarse la operación bajo las condiciones
         *     requeridas.
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
         * Elimina el recurso solicitado mediante {@code delete}.
         *
         * @param objectKey Valor de {@code objectKey} utilizado por la operación.
         */
        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }

    }

    /**
     * Agrupa los escenarios de prueba de {@code RecordingPublisher}.
     *
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
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
         * Inicializa una instancia de {@code RecordingPublisher}.
         */
        private RecordingPublisher() {
            this(null);
        }

        /**
         * Inicializa una instancia de {@code RecordingPublisher}.
         *
         * @param fastTerminalPublished Valor de {@code fastTerminalPublished} utilizado por la
         *     operación.
         */
        private RecordingPublisher(CountDownLatch fastTerminalPublished) {
            this.fastTerminalPublished = fastTerminalPublished;
        }

        /**
         * Publica el contenido solicitado mediante {@code publish}.
         *
         * @param routingKey Valor de {@code routingKey} utilizado por la operación.
         * @param event Evento que debe procesarse.
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
