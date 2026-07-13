package es.ubu.batchdownloader.downloadworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadFailedPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobFailedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobProgressedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobReadyEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadProgressPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadReadyPayload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadManifest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ManifestItem;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.infrastructure.Hashing;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DownloadJobProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobProcessor.class);

    private final SourceReferenceResolver sourceResolver;
    private final RemoteDownloader remoteDownloader;
    private final ArtifactStore artifactStore;
    private final ArchiveBuilder archiveBuilder;
    private final EventPublisher eventPublisher;
    private final FilenamePolicy filenamePolicy;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final DownloadProperties properties;
    private final StorageProperties storageProperties;
    private final Clock clock;

    public DownloadJobProcessor(
            SourceReferenceResolver sourceResolver,
            RemoteDownloader remoteDownloader,
            ArtifactStore artifactStore,
            ArchiveBuilder archiveBuilder,
            EventPublisher eventPublisher,
            FilenamePolicy filenamePolicy,
            ObjectMapper objectMapper,
            ExecutorService executor,
            DownloadProperties properties,
            StorageProperties storageProperties,
            Clock clock) {
        this.sourceResolver = sourceResolver;
        this.remoteDownloader = remoteDownloader;
        this.artifactStore = artifactStore;
        this.archiveBuilder = archiveBuilder;
        this.eventPublisher = eventPublisher;
        this.filenamePolicy = filenamePolicy;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.clock = clock;
    }

    public void process(DownloadJobRequestedEvent event) {
        String invalidReason = validateJob(event);
        if (invalidReason != null) {
            publishJobFailure(event, invalidReason, event.payload().items().size());
            return;
        }

        UUID jobId = event.payload().jobId();
        Path jobDirectory = createJobDirectory(jobId);
        try {
            ProcessedDownloads processed = downloadItems(event, jobDirectory);
            List<DownloadedArtifact> stored = storeFiles(jobId, processed.downloaded());
            String status = stored.isEmpty() ? "FAILED" : processed.failed().isEmpty() ? "READY" : "PARTIAL";
            Path manifestPath = writeManifest(
                    event, status, stored, processed.failed(), jobDirectory.resolve("manifest.json"));
            Path zipPath = jobDirectory.resolve("batch-downloader-" + jobId + ".zip");
            archiveBuilder.build(zipPath, stored, manifestPath);

            String prefix = "jobs/" + jobId;
            String manifestObjectKey = prefix + "/manifest.json";
            String zipObjectKey = prefix + "/bundle.zip";
            artifactStore.put(manifestObjectKey, manifestPath, "application/json");
            artifactStore.put(zipObjectKey, zipPath, "application/zip");
            publishResultEvents(event, status, stored, processed.failed(), zipPath, zipObjectKey);
        } finally {
            deleteRecursively(jobDirectory);
        }
    }

    private String validateJob(DownloadJobRequestedEvent event) {
        if (event.payload().items().size() > properties.maxItems()) {
            return "too_many_items";
        }
        Set<UUID> itemIds = new HashSet<>();
        for (DownloadItemRequest item : event.payload().items()) {
            if (!itemIds.add(item.itemId())) {
                return "duplicate_item_id";
            }
        }
        return null;
    }

    private ProcessedDownloads downloadItems(DownloadJobRequestedEvent event, Path jobDirectory) {
        long maxJobBytes = Math.min(
                event.payload().limits().maxJobBytes(), properties.maxTotalSize().toBytes());
        long maxFileBytes = Math.min(
                event.payload().limits().maxFileBytes(), properties.maxFileSize().toBytes());
        int parallelism = Math.min(
                event.payload().limits().maxParallelDownloads(), properties.concurrency());
        DownloadBudget totalBudget = new DownloadBudget(maxJobBytes);
        Semaphore semaphore = new Semaphore(parallelism);
        Set<String> usedNames = filenamePolicy.newNameSet();
        List<Future<DownloadAttempt>> futures = new ArrayList<>();
        for (DownloadItemRequest item : event.payload().items()) {
            futures.add(executor.submit(() -> downloadOne(
                    item, jobDirectory, totalBudget, maxFileBytes, semaphore, usedNames)));
        }

        List<DownloadedArtifact> downloaded = new ArrayList<>();
        List<FailedDownload> failed = new ArrayList<>();
        try {
            for (Future<DownloadAttempt> future : futures) {
                DownloadAttempt attempt = await(future);
                if (attempt.artifact() != null) {
                    downloaded.add(attempt.artifact());
                } else {
                    failed.add(attempt.failure());
                }
            }
        } catch (RuntimeException exception) {
            futures.forEach(future -> future.cancel(true));
            throw exception;
        }
        return new ProcessedDownloads(downloaded, failed);
    }

    private DownloadAttempt downloadOne(
            DownloadItemRequest item,
            Path jobDirectory,
            DownloadBudget totalBudget,
            long maxFileBytes,
            Semaphore semaphore,
            Set<String> usedNames) {
        boolean acquired = false;
        String filename = "installer-" + item.itemId() + ".bin";
        try {
            semaphore.acquire();
            acquired = true;
            ResolvedDownloadItem resolved = sourceResolver.resolve(item);
            synchronized (usedNames) {
                filename = filenamePolicy.filenameFor(resolved, usedNames);
            }
            Path target = jobDirectory.resolve("files").resolve(filename);
            return DownloadAttempt.success(remoteDownloader.download(
                    resolved, filename, target, totalBudget, maxFileBytes));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        } catch (DownloadRejectedException rejected) {
            return DownloadAttempt.failure(new FailedDownload(
                    item.itemId(), item.appId(), item.sourceRef(), filename, rejected.code()));
        } finally {
            if (acquired) {
                semaphore.release();
            }
        }
    }

    private DownloadAttempt await(Future<DownloadAttempt> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new InfrastructureException("download_task_failed", cause);
        }
    }

    private List<DownloadedArtifact> storeFiles(UUID jobId, List<DownloadedArtifact> downloads) {
        List<DownloadedArtifact> stored = new ArrayList<>();
        for (DownloadedArtifact artifact : downloads) {
            String objectKey = "jobs/" + jobId + "/files/" + artifact.filename();
            artifactStore.put(objectKey, artifact.path(), "application/octet-stream");
            stored.add(new DownloadedArtifact(
                    artifact.itemId(), artifact.appId(), artifact.sourceRef(), artifact.filename(),
                    artifact.path(), artifact.sizeBytes(), artifact.sha256(), objectKey));
        }
        return stored;
    }

    private Path writeManifest(
            DownloadJobRequestedEvent event,
            String status,
            List<DownloadedArtifact> downloaded,
            List<FailedDownload> failed,
            Path target) {
        Map<UUID, ManifestItem> items = new HashMap<>();
        for (DownloadedArtifact artifact : downloaded) {
            items.put(artifact.itemId(), new ManifestItem(
                    artifact.itemId(), artifact.appId(), artifact.sourceRef(), artifact.filename(),
                    "COMPLETED", artifact.sizeBytes(), artifact.sha256(), artifact.objectKey(), null));
        }
        for (FailedDownload failure : failed) {
            items.put(failure.itemId(), new ManifestItem(
                    failure.itemId(), failure.appId(), failure.sourceRef(), failure.filename(),
                    "FAILED", null, null, null, failure.errorCode()));
        }
        List<ManifestItem> ordered = event.payload().items().stream()
                .map(item -> items.get(item.itemId()))
                .toList();
        DownloadManifest manifest = new DownloadManifest(
                EventTypes.CURRENT_VERSION, event.payload().jobId(), clock.instant(), status, ordered);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), manifest);
            return target;
        } catch (IOException exception) {
            throw new InfrastructureException("manifest_creation_failed", exception);
        }
    }

    private void publishResultEvents(
            DownloadJobRequestedEvent event,
            String status,
            List<DownloadedArtifact> downloaded,
            List<FailedDownload> failed,
            Path zipPath,
            String zipObjectKey) {
        Instant occurredAt = clock.instant();
        for (DownloadedArtifact artifact : downloaded) {
            publishProgress(event, occurredAt, artifact.itemId(), "COMPLETED",
                    artifact.sizeBytes(), artifact.sizeBytes(), artifact.sha256(), null);
        }
        for (FailedDownload failure : failed) {
            publishProgress(event, occurredAt, failure.itemId(), "FAILED", 0, null, null, failure.errorCode());
        }

        if (downloaded.isEmpty()) {
            publishJobFailure(event, "all_downloads_failed", failed.size());
            return;
        }

        Duration ttl = storageProperties.presignedUrlTtl().compareTo(Duration.ofDays(7)) > 0
                ? Duration.ofDays(7)
                : storageProperties.presignedUrlTtl();
        DownloadReadyPayload payload = new DownloadReadyPayload(
                event.payload().jobId(),
                "READY".equals(status) ? "READY" : "PARTIAL",
                zipObjectKey,
                fileSize(zipPath),
                Hashing.sha256(zipPath),
                downloaded.size(),
                failed.size(),
                occurredAt.plus(ttl));
        eventPublisher.publish(EventTypes.JOB_READY_ROUTING_KEY, new DownloadJobReadyEvent(
                deterministicEventId(event.payload().jobId(), EventTypes.JOB_READY, "bundle"),
                EventTypes.JOB_READY,
                EventTypes.CURRENT_VERSION,
                occurredAt,
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

    private void publishProgress(
            DownloadJobRequestedEvent event,
            Instant occurredAt,
            UUID itemId,
            String status,
            long bytesDownloaded,
            Long sizeBytes,
            String sha256,
            String errorCode) {
        DownloadProgressPayload payload = new DownloadProgressPayload(
                event.payload().jobId(), itemId, status, bytesDownloaded, sizeBytes, sha256, errorCode);
        eventPublisher.publish(EventTypes.JOB_PROGRESSED_ROUTING_KEY, new DownloadJobProgressedEvent(
                deterministicEventId(event.payload().jobId(), EventTypes.JOB_PROGRESSED, itemId.toString()),
                EventTypes.JOB_PROGRESSED,
                EventTypes.CURRENT_VERSION,
                occurredAt,
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

    private void publishJobFailure(DownloadJobRequestedEvent event, String code, int failedItems) {
        DownloadFailedPayload payload = new DownloadFailedPayload(
                event.payload().jobId(), code, Math.max(1, failedItems));
        eventPublisher.publish(EventTypes.JOB_FAILED_ROUTING_KEY, new DownloadJobFailedEvent(
                deterministicEventId(event.payload().jobId(), EventTypes.JOB_FAILED, code),
                EventTypes.JOB_FAILED,
                EventTypes.CURRENT_VERSION,
                clock.instant(),
                event.correlationId(),
                event.eventId().toString(),
                payload));
    }

    private UUID deterministicEventId(UUID jobId, String type, String discriminator) {
        return UUID.nameUUIDFromBytes(
                (jobId + ":" + type + ":" + discriminator).getBytes(StandardCharsets.UTF_8));
    }

    private Path createJobDirectory(UUID jobId) {
        try {
            Path base = Path.of(properties.tempDirectory());
            Files.createDirectories(base);
            return Files.createTempDirectory(base, jobId + "-");
        } catch (IOException exception) {
            throw new InfrastructureException("temp_directory_creation_failed", exception);
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new InfrastructureException("file_size_read_failed", exception);
        }
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOGGER.debug("Could not delete temporary download path {}", path, exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.debug("Could not traverse temporary download directory {}", root, exception);
        }
    }

    private record DownloadAttempt(DownloadedArtifact artifact, FailedDownload failure) {
        static DownloadAttempt success(DownloadedArtifact artifact) {
            return new DownloadAttempt(artifact, null);
        }

        static DownloadAttempt failure(FailedDownload failure) {
            return new DownloadAttempt(null, failure);
        }
    }

    private record ProcessedDownloads(List<DownloadedArtifact> downloaded, List<FailedDownload> failed) {
    }
}
