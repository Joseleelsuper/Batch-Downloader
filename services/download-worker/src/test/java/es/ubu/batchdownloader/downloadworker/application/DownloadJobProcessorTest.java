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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class DownloadJobProcessorTest {
    private static final UUID GOOD_ITEM_ID = id("item-ok");
    private static final UUID BAD_ITEM_ID = id("item-bad");
    private static final UUID FAST_ITEM_ID = id("item-fast");
    private static final UUID SLOW_ITEM_ID = id("item-slow");
    @TempDir
    Path temp;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

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
        assertThat(manifest).contains("\"status\" : \"PARTIAL\"")
                .contains("remote_http_404")
                .contains("\"appName\" : \"Bad app\"")
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

    private DownloadJobProcessor processor(
            RemoteDownloader downloader,
            ArtifactStore store,
            EventPublisher publisher,
            int maxItems) {
        return processor(downloader, store, publisher, maxItems, metadataLookup(true));
    }

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
                null,
                null,
                null);
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
                new DownloadCancellationRegistry());
    }

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

    private static InetAddress publicAddress() {
        try {
            return InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        } catch (java.net.UnknownHostException exception) {
            throw new AssertionError(exception);
        }
    }

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

    private DownloadItemRequest item(String id, String filename) {
        return new DownloadItemRequest(
                DownloadJobProcessorTest.id("item-" + id),
                DownloadJobProcessorTest.id("app-" + id),
                DownloadJobProcessorTest.id("source-" + id));
    }

    private static String filename(UUID itemId) {
        return BAD_ITEM_ID.equals(itemId) ? "Bad.exe" : "Good.exe";
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static class MemoryArtifactStore implements ArtifactStore {
        private final Map<String, byte[]> objects = new HashMap<>();

        @Override
        public void put(String objectKey, Path source, String contentType) {
            try {
                objects.put(objectKey, Files.readAllBytes(source));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
        }

    }

    private static class RecordingPublisher implements EventPublisher {
        private final List<String> routingKeys = new ArrayList<>();
        private final List<Object> events = new ArrayList<>();
        private final CountDownLatch fastTerminalPublished;

        private RecordingPublisher() {
            this(null);
        }

        private RecordingPublisher(CountDownLatch fastTerminalPublished) {
            this.fastTerminalPublished = fastTerminalPublished;
        }

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
