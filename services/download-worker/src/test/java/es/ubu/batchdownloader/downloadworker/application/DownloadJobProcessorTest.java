package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobReadyEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadLimits;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.infrastructure.Hashing;
import es.ubu.batchdownloader.downloadworker.infrastructure.archive.ZipArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class DownloadJobProcessorTest {
    private static final UUID GOOD_ITEM_ID = id("item-ok");
    private static final UUID BAD_ITEM_ID = id("item-bad");
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
                "jobs/" + event.payload().jobId() + "/files/Good.exe",
                "jobs/" + event.payload().jobId() + "/manifest.json",
                "jobs/" + event.payload().jobId() + "/bundle.zip");
        String manifest = new String(store.objects.get("jobs/" + event.payload().jobId() + "/manifest.json"));
        assertThat(manifest).contains("\"status\" : \"PARTIAL\"")
                .contains("remote_http_404")
                .contains("Good.exe");
        assertThat(publisher.routingKeys).containsExactly(
                EventTypes.JOB_PROGRESSED_ROUTING_KEY,
                EventTypes.JOB_PROGRESSED_ROUTING_KEY,
                EventTypes.JOB_READY_ROUTING_KEY);
        DownloadJobReadyEvent readyEvent = (DownloadJobReadyEvent) publisher.events.get(2);
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

    private DownloadJobProcessor processor(
            RemoteDownloader downloader,
            ArtifactStore store,
            EventPublisher publisher,
            int maxItems) {
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
                item.operatingSystem(),
                item.architecture(),
                null,
                null,
                null);
        return new DownloadJobProcessor(
                resolver,
                downloader,
                store,
                new ZipArchiveBuilder(),
                publisher,
                new FilenamePolicy(),
                mapper,
                executor,
                downloadProperties,
                storage,
                Clock.fixed(Instant.parse("2026-07-11T12:00:00Z"), ZoneOffset.UTC));
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
                        items,
                        new DownloadLimits(10_000_000, 20_000_000, 2)));
    }

    private DownloadItemRequest item(String id, String filename) {
        return new DownloadItemRequest(
                DownloadJobProcessorTest.id("item-" + id),
                DownloadJobProcessorTest.id("app-" + id),
                DownloadJobProcessorTest.id("source-" + id),
                "windows",
                "x86_64");
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

    }

    private static class RecordingPublisher implements EventPublisher {
        private final List<String> routingKeys = new ArrayList<>();
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publish(String routingKey, Object event) {
            routingKeys.add(routingKey);
            events.add(event);
        }
    }
}
