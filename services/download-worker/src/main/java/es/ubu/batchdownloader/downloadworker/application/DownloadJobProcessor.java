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
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadManifest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ManifestItem;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.infrastructure.Hashing;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DownloadJobProcessor {
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobProcessor.class);
    private static final Set<String> SENSITIVE_QUERY_NAMES = Set.of(
            "access_token",
            "api_key",
            "apikey",
            "auth",
            "authorization",
            "key",
            "password",
            "sig",
            "signature",
            "token");
    private static final Pattern SENSITIVE_QUERY_MARKER = Pattern.compile(
            "access_?key|api_?key|authorization|credential|password|secret|signature|token");

    private final SourceReferenceResolver sourceResolver;
    private final JobItemMetadataLookup metadataLookup;
    private final RemoteDownloader remoteDownloader;
    private final ArtifactStore artifactStore;
    private final ArchiveBuilder archiveBuilder;
    private final EventPublisher eventPublisher;
    private final FilenamePolicy filenamePolicy;
    private final PublicHttpsUriPolicy publicHttpsUriPolicy;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;
    private final DownloadProperties properties;
    private final StorageProperties storageProperties;
    private final Clock clock;
    private final DownloadCancellationRegistry cancellations;

    public DownloadJobProcessor(
            SourceReferenceResolver sourceResolver,
            JobItemMetadataLookup metadataLookup,
            RemoteDownloader remoteDownloader,
            ArtifactStore artifactStore,
            ArchiveBuilder archiveBuilder,
            EventPublisher eventPublisher,
            FilenamePolicy filenamePolicy,
            PublicHttpsUriPolicy publicHttpsUriPolicy,
            ObjectMapper objectMapper,
            ExecutorService executor,
            DownloadProperties properties,
            StorageProperties storageProperties,
            Clock clock,
            DownloadCancellationRegistry cancellations) {
        this.sourceResolver = sourceResolver;
        this.metadataLookup = metadataLookup;
        this.remoteDownloader = remoteDownloader;
        this.artifactStore = artifactStore;
        this.archiveBuilder = archiveBuilder;
        this.eventPublisher = eventPublisher;
        this.filenamePolicy = filenamePolicy;
        this.publicHttpsUriPolicy = publicHttpsUriPolicy;
        this.objectMapper = objectMapper;
        this.executor = executor;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.clock = clock;
        this.cancellations = cancellations;
    }

    public void process(DownloadJobRequestedEvent event) {
        String invalidReason = validateJob(event);
        if (invalidReason != null) {
            publishJobFailure(event, invalidReason, event.payload().items().size());
            return;
        }

        UUID jobId = event.payload().jobId();
        if (cancellations.cancelled(jobId)) {
            cancellations.finish(jobId);
            return;
        }
        Path jobDirectory = createJobDirectory(jobId);
        List<DownloadedArtifact> stagedArtifacts = List.of();
        try {
            ProcessedDownloads processed = downloadItems(event, jobDirectory);
            if (cancellations.cancelled(jobId)) {
                return;
            }
            stagedArtifacts = storeFiles(jobId, processed.downloaded());
            if (cancellations.cancelled(jobId)) {
                return;
            }
            Map<UUID, DownloadItemMetadata> failedMetadata = failedMetadata(event, processed.failed());
            ManualShortcuts manualShortcuts =
                    writeManualShortcuts(processed.failed(), failedMetadata, jobDirectory);
            if (stagedArtifacts.isEmpty() && manualShortcuts.entries().isEmpty()) {
                publishJobFailure(event, "all_downloads_failed", processed.failed().size());
                return;
            }
            String status = stagedArtifacts.isEmpty()
                    ? "MANUAL_ONLY"
                    : processed.failed().isEmpty() ? "READY" : "PARTIAL";
            Path manifestPath = writeManifest(
                    event,
                    status,
                    stagedArtifacts,
                    processed.failed(),
                    failedMetadata,
                    manualShortcuts.pathsByItem(),
                    jobDirectory.resolve("manifest.json"));
            Path zipPath = jobDirectory.resolve("batch-downloader-" + jobId + ".zip");
            archiveBuilder.build(zipPath, stagedArtifacts, manualShortcuts.entries(), manifestPath);
            if (cancellations.cancelled(jobId)) {
                return;
            }

            String prefix = "jobs/" + jobId;
            String manifestObjectKey = prefix + "/manifest.json";
            String zipObjectKey = prefix + "/bundle.zip";
            artifactStore.put(manifestObjectKey, manifestPath, "application/json");
            artifactStore.put(zipObjectKey, zipPath, "application/zip");
            List<DownloadedArtifact> completedArtifacts = stagedArtifacts;
            deleteStagedArtifacts(stagedArtifacts);
            stagedArtifacts = List.of();
            if (cancellations.cancelled(jobId)) {
                return;
            }
            publishReadyEvent(
                    event,
                    status,
                    completedArtifacts.size(),
                    processed.failed().size(),
                    zipPath,
                    zipObjectKey);
        } finally {
            deleteStagedArtifacts(stagedArtifacts);
            cancellations.finish(jobId);
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
        long maxJobBytes = properties.maxTotalSize().toBytes();
        long maxFileBytes = properties.maxFileSize().toBytes();
        int parallelism = properties.concurrency();
        DownloadBudget totalBudget = new DownloadBudget(maxJobBytes);
        Semaphore semaphore = new Semaphore(parallelism);
        Set<String> usedNames = filenamePolicy.newNameSet();
        CompletionService<DownloadAttempt> completions = new ExecutorCompletionService<>(executor);
        List<Future<DownloadAttempt>> futures = new ArrayList<>();
        for (DownloadItemRequest item : event.payload().items()) {
            if (cancellations.cancelled(event.payload().jobId())) {
                break;
            }
            publishProgress(event, clock.instant(), item.itemId(), "RESOLVING", 0, null, null, null);
            futures.add(completions.submit(() -> downloadOne(
                    event, item, jobDirectory, totalBudget, maxFileBytes, semaphore, usedNames)));
        }
        cancellations.track(event.payload().jobId(), futures);

        List<DownloadedArtifact> downloaded = new ArrayList<>();
        List<FailedDownload> failed = new ArrayList<>();
        try {
            for (int index = 0; index < futures.size(); index++) {
                DownloadAttempt attempt = awaitCompleted(completions);
                if (cancellations.cancelled(event.payload().jobId())) {
                    return new ProcessedDownloads(List.of(), List.of());
                }
                if (attempt.artifact() != null) {
                    downloaded.add(attempt.artifact());
                    DownloadedArtifact artifact = attempt.artifact();
                    publishProgress(
                            event,
                            clock.instant(),
                            artifact.itemId(),
                            "COMPLETED",
                            artifact.sizeBytes(),
                            artifact.sizeBytes(),
                            artifact.sha256(),
                            null);
                } else {
                    failed.add(attempt.failure());
                    publishProgress(
                            event,
                            clock.instant(),
                            attempt.failure().itemId(),
                            "FAILED",
                            0,
                            null,
                            null,
                            attempt.failure().errorCode());
                }
            }
        } catch (CancellationException exception) {
            if (cancellations.cancelled(event.payload().jobId())) {
                return new ProcessedDownloads(List.of(), List.of());
            }
            throw exception;
        } catch (RuntimeException exception) {
            futures.forEach(future -> future.cancel(true));
            throw exception;
        }
        return new ProcessedDownloads(downloaded, failed);
    }

    private DownloadAttempt awaitCompleted(CompletionService<DownloadAttempt> completions) {
        try {
            return await(completions.take());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    private DownloadAttempt downloadOne(
            DownloadJobRequestedEvent event,
            DownloadItemRequest item,
            Path jobDirectory,
            DownloadBudget totalBudget,
            long maxFileBytes,
            Semaphore semaphore,
            Set<String> usedNames) {
        boolean acquired = false;
        String filename = "installer-" + item.itemId() + ".bin";
        try {
            if (cancellations.cancelled(event.payload().jobId())) {
                return cancelledAttempt(item, filename);
            }
            semaphore.acquire();
            acquired = true;
            if (cancellations.cancelled(event.payload().jobId())) {
                return cancelledAttempt(item, filename);
            }
            ResolvedDownloadItem resolved = sourceResolver.resolve(item);
            if (cancellations.cancelled(event.payload().jobId())) {
                return cancelledAttempt(item, filename);
            }
            publishProgress(event, clock.instant(), item.itemId(), "DOWNLOADING", 0,
                    resolved.expectedSizeBytes(), null, null);
            synchronized (usedNames) {
                filename = filenamePolicy.filenameFor(resolved, usedNames);
            }
            Path target = jobDirectory.resolve("files").resolve(filename);
            DownloadedArtifact downloaded = remoteDownloader.download(
                    resolved, filename, target, totalBudget, maxFileBytes);
            return cancellations.cancelled(event.payload().jobId())
                    ? cancelledAttempt(item, filename)
                    : DownloadAttempt.success(downloaded);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (cancellations.cancelled(event.payload().jobId())) {
                return cancelledAttempt(item, filename);
            }
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

    private DownloadAttempt cancelledAttempt(DownloadItemRequest item, String filename) {
        return DownloadAttempt.failure(new FailedDownload(
                item.itemId(), item.appId(), item.sourceRef(), filename, "cancelled"));
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

    private Map<UUID, DownloadItemMetadata> failedMetadata(
            DownloadJobRequestedEvent event,
            List<FailedDownload> failures) {
        if (failures.isEmpty()) {
            return Map.of();
        }
        Set<UUID> failedItemIds = failures.stream()
                .map(FailedDownload::itemId)
                .collect(java.util.stream.Collectors.toSet());
        List<DownloadItemRequest> failedItems = event.payload().items().stream()
                .filter(item -> failedItemIds.contains(item.itemId()))
                .toList();
        if (failedItems.size() != failedItemIds.size()) {
            throw new InfrastructureException(
                    "invalid_failed_download_items",
                    new IllegalStateException("Failed items do not match the job command"));
        }
        return metadataLookup.find(event.payload().jobId(), failedItems);
    }

    private ManualShortcuts writeManualShortcuts(
            List<FailedDownload> failures,
            Map<UUID, DownloadItemMetadata> metadata,
            Path jobDirectory) {
        if (failures.isEmpty()) {
            return new ManualShortcuts(List.of(), Map.of());
        }
        List<ArchiveEntry> entries = new ArrayList<>();
        Map<UUID, String> pathsByItem = new HashMap<>();
        Set<String> usedNames = filenamePolicy.newNameSet();
        Path shortcutsDirectory = jobDirectory.resolve("manual-shortcuts");
        for (FailedDownload failure : failures) {
            DownloadItemMetadata item = metadata.get(failure.itemId());
            URI officialPage = safeOfficialPage(item == null ? null : item.officialPageUrl());
            if (officialPage == null) {
                continue;
            }
            String filename = filenamePolicy.manualShortcutFilename(item.appName(), usedNames);
            Path shortcut = shortcutsDirectory.resolve(filename);
            try {
                Files.createDirectories(shortcutsDirectory);
                Files.writeString(
                        shortcut,
                        "[InternetShortcut]\r\nURL=" + officialPage.toASCIIString() + "\r\n",
                        StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new InfrastructureException("manual_shortcut_creation_failed", exception);
            }
            String archivePath = "Descargas manuales/" + filename;
            entries.add(new ArchiveEntry(archivePath, shortcut));
            pathsByItem.put(failure.itemId(), archivePath);
        }
        return new ManualShortcuts(List.copyOf(entries), Map.copyOf(pathsByItem));
    }

    private URI safeOfficialPage(String value) {
        if (value == null
                || value.isBlank()
                || value.chars().anyMatch(character -> character < 32 || character == 127)) {
            return null;
        }
        try {
            URI uri = URI.create(value.strip());
            if (hasSensitiveQuery(uri)) {
                return null;
            }
            publicHttpsUriPolicy.validate(uri);
            return uri;
        } catch (IllegalArgumentException | DownloadRejectedException exception) {
            return null;
        }
    }

    private boolean hasSensitiveQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return false;
        }
        try {
            for (String parameter : query.split("&")) {
                String rawName = parameter.split("=", 2)[0];
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8)
                        .toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", "_")
                        .replaceAll("^_+|_+$", "");
                if (SENSITIVE_QUERY_NAMES.contains(name)
                        || SENSITIVE_QUERY_MARKER.matcher(name).find()) {
                    return true;
                }
            }
            return false;
        } catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private void deleteStagedArtifacts(List<DownloadedArtifact> artifacts) {
        for (DownloadedArtifact artifact : artifacts) {
            try {
                artifactStore.delete(artifact.objectKey());
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not delete staged object {}", artifact.objectKey(), exception);
            }
        }
    }

    private Path writeManifest(
            DownloadJobRequestedEvent event,
            String status,
            List<DownloadedArtifact> downloaded,
            List<FailedDownload> failed,
            Map<UUID, DownloadItemMetadata> failedMetadata,
            Map<UUID, String> manualShortcutPaths,
            Path target) {
        Map<UUID, ManifestItem> items = new HashMap<>();
        for (DownloadedArtifact artifact : downloaded) {
            items.put(artifact.itemId(), new ManifestItem(
                    artifact.itemId(), artifact.appId(), artifact.sourceRef(), null, artifact.filename(),
                    "COMPLETED", artifact.sizeBytes(), artifact.sha256(), artifact.objectKey(), null, null));
        }
        for (FailedDownload failure : failed) {
            DownloadItemMetadata metadata = failedMetadata.get(failure.itemId());
            items.put(failure.itemId(), new ManifestItem(
                    failure.itemId(),
                    failure.appId(),
                    failure.sourceRef(),
                    metadata == null ? failure.appId().toString() : metadata.appName(),
                    failure.filename(),
                    "FAILED",
                    null,
                    null,
                    null,
                    failure.errorCode(),
                    manualShortcutPaths.get(failure.itemId())));
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

    private void publishReadyEvent(
            DownloadJobRequestedEvent event,
            String status,
            int successfulItems,
            int failedItems,
            Path zipPath,
            String zipObjectKey) {
        Instant occurredAt = clock.instant();
        Duration ttl = storageProperties.presignedUrlTtl().compareTo(Duration.ofDays(7)) > 0
                ? Duration.ofDays(7)
                : storageProperties.presignedUrlTtl();
        DownloadReadyPayload payload = new DownloadReadyPayload(
                event.payload().jobId(),
                status,
                zipObjectKey,
                fileSize(zipPath),
                Hashing.sha256(zipPath),
                successfulItems,
                failedItems,
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
                deterministicEventId(
                        event.payload().jobId(),
                        EventTypes.JOB_PROGRESSED,
                        itemId + ":" + status.toLowerCase(java.util.Locale.ROOT)),
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

    private record ManualShortcuts(
            List<ArchiveEntry> entries,
            Map<UUID, String> pathsByItem) {
    }
}
