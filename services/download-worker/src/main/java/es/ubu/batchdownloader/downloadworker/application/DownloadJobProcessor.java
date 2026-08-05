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
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadManifest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ManifestItem;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.domain.EventTypes;
import es.ubu.batchdownloader.downloadworker.infrastructure.http.PublicHttpsUriPolicy;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore.StoredArtifact;
import es.ubu.batchdownloader.downloadworker.ports.EventPublisher;
import es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import io.micrometer.core.instrument.Timer;
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
import java.util.LinkedHashSet;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Descarga y empaqueta un trabajo con ventanas acotadas y subida directa a MinIO.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Service
public class DownloadJobProcessor {
    /** Registro de diagnóstico. */
    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadJobProcessor.class);
    /** Versión del manifiesto interno del ZIP. */
    private static final int MANIFEST_VERSION = 2;
    /** Nombres de parámetros que nunca deben conservarse en accesos manuales. */
    private static final Set<String> SENSITIVE_QUERY_NAMES = Set.of(
            "access_token", "api_key", "apikey", "auth", "authorization", "key",
            "password", "sig", "signature", "token");
    /** Detector defensivo de nombres de credenciales. */
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
    private final JobCapacity jobCapacity;
    private final Semaphore packagingSemaphore;
    private final DownloadWorkerMetrics metrics;
    /** Reserva global del SSD para los temporales en vuelo. */
    private final TemporaryDiskCapacity diskCapacity;

    /** Inicializa todas las dependencias del pipeline. */
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
            DownloadCancellationRegistry cancellations,
            JobCapacity jobCapacity,
            @Qualifier("packagingSemaphore") Semaphore packagingSemaphore,
            DownloadWorkerMetrics metrics,
            TemporaryDiskCapacity diskCapacity) {
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
        this.jobCapacity = jobCapacity;
        this.packagingSemaphore = packagingSemaphore;
        this.metrics = metrics;
        this.diskCapacity = diskCapacity;
    }

    /**
     * Procesa un evento validado por el listener.
     *
     * @param event Solicitud de trabajo.
     */
    public void process(DownloadJobRequestedEvent event) {
        String invalidReason = validateJob(event);
        if (invalidReason != null) {
            publishJobFailure(event, invalidReason, event.payload().items().size());
            return;
        }
        UUID jobId = event.payload().jobId();
        Path jobDirectory = null;
        String prefix = "jobs/" + jobId;
        String manifestObjectKey = prefix + "/manifest.json";
        String zipObjectKey = prefix + "/bundle.zip";
        boolean readyPublished = false;
        try {
            if (cancellations.cancelled(jobId)) {
                return;
            }
            diskCapacity.requireAvailable(Path.of(properties.tempDirectory()));
            PreparedDownloads prepared = resolveItems(event);
            if (cancellations.cancelled(jobId)) {
                return;
            }
            int weight = capacityWeight(prepared.resolved());
            try (JobCapacity.Lease ignored = jobCapacity.acquire(weight)) {
                jobDirectory = createJobDirectory(jobId);
                try (TemporaryDiskCapacity.Lease ignoredDisk =
                        diskCapacity.reserve(jobDirectory, 0L)) {
                    // La adquisición comprueba la reserva mínima antes de iniciar el trabajo.
                }
                Path activeDirectory = jobDirectory;
                int window = weight > 1 ? 1 : properties.perJobConcurrency();
                DownloadPipeline pipeline = new DownloadPipeline(
                        event, prepared.resolved(), activeDirectory, window);
                Timer.Sample wait = metrics.startPackagingWait();
                acquirePackaging();
                metrics.stopPackagingWait(wait);
                try {
                    AtomicReference<ArchiveOutcome> outcomeReference = new AtomicReference<>();
                    StoredArtifact storedZip = artifactStore.putStreaming(
                            zipObjectKey,
                            "application/zip",
                            properties.multipartPartSize().toBytes(),
                            output -> archiveBuilder.build(
                                    output,
                                    properties.zipLevel(),
                                    writer -> outcomeReference.set(writeArchive(
                                            event, prepared.failed(), pipeline, writer, activeDirectory))));
                    ArchiveOutcome outcome = outcomeReference.get();
                    if (outcome == null) {
                        throw new InfrastructureException(
                                "zip_outcome_missing", new IllegalStateException("Archive produced no result"));
                    }
                    if (cancellations.cancelled(jobId)) {
                        return;
                    }
                    artifactStore.putBytes(
                            manifestObjectKey,
                            outcome.manifest(),
                            "application/json",
                            properties.multipartPartSize().toBytes());
                    if (cancellations.cancelled(jobId)) {
                        return;
                    }
                    publishReadyEvent(event, outcome, storedZip, zipObjectKey);
                    readyPublished = true;
                } catch (AllDownloadsFailedException exception) {
                    publishJobFailure(event, "all_downloads_failed", exception.failedItems());
                } catch (CancellationException exception) {
                    if (!cancellations.cancelled(jobId)) {
                        throw exception;
                    }
                } finally {
                    packagingSemaphore.release();
                }
            }
        } catch (CancellationException exception) {
            if (!cancellations.cancelled(jobId)) {
                throw exception;
            }
        } finally {
            if (!readyPublished) {
                deleteStoredObject(zipObjectKey);
                deleteStoredObject(manifestObjectKey);
            }
            cancellations.finish(jobId);
            if (jobDirectory != null) {
                metrics.temporaryRemoved(directorySize(jobDirectory));
                deleteRecursively(jobDirectory);
            }
        }
    }

    /** Valida cardinalidad e identificadores duplicados. */
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

    /**
     * Resuelve las fuentes con una ventana acotada antes de decidir si el trabajo es exclusivo.
     */
    private PreparedDownloads resolveItems(DownloadJobRequestedEvent event) {
        List<DownloadItemRequest> items = event.payload().items();
        CompletionService<ResolutionAttempt> completions = new ExecutorCompletionService<>(executor);
        List<Future<ResolutionAttempt>> futures = new ArrayList<>();
        int next = 0;
        int completed = 0;
        int window = Math.min(properties.perJobConcurrency(), items.size());
        while (next < window) {
            submitResolution(event, items.get(next++), completions, futures);
        }
        List<ResolvedDownloadItem> resolved = new ArrayList<>();
        List<FailedDownload> failed = new ArrayList<>();
        while (completed < items.size()) {
            ResolutionAttempt attempt = awaitCompleted(completions);
            completed++;
            if (attempt.resolved() != null) {
                resolved.add(attempt.resolved());
            } else {
                failed.add(attempt.failure());
                publishProgress(
                        event, clock.instant(), attempt.failure().itemId(), "FAILED",
                        0, null, null, attempt.failure().errorCode());
            }
            if (next < items.size()) {
                submitResolution(event, items.get(next++), completions, futures);
            }
            if (cancellations.cancelled(event.payload().jobId())) {
                futures.forEach(future -> future.cancel(true));
                throw new CancellationException("download_job_cancelled");
            }
        }
        return new PreparedDownloads(List.copyOf(resolved), List.copyOf(failed));
    }

    /** Envía una resolución al ejecutor global y actualiza el registro de cancelación. */
    private void submitResolution(
            DownloadJobRequestedEvent event,
            DownloadItemRequest item,
            CompletionService<ResolutionAttempt> completions,
            List<Future<ResolutionAttempt>> futures) {
        publishProgress(event, clock.instant(), item.itemId(), "RESOLVING", 0, null, null, null);
        try {
            futures.add(completions.submit(() -> resolveOne(item)));
            cancellations.track(event.payload().jobId(), futures);
        } catch (RejectedExecutionException exception) {
            throw new InfrastructureException("download_executor_saturated", exception);
        }
    }

    /** Resuelve una fuente y convierte únicamente los rechazos funcionales en fallo de item. */
    private ResolutionAttempt resolveOne(DownloadItemRequest item) {
        try {
            return ResolutionAttempt.success(sourceResolver.resolve(item));
        } catch (DownloadRejectedException exception) {
            return ResolutionAttempt.failure(new FailedDownload(
                    item.itemId(), item.appId(), item.sourceRef(),
                    "installer-" + item.itemId() + ".bin", exception.code()));
        }
    }

    /** Calcula el peso usando tamaños declarados; cualquier desconocido fuerza exclusividad. */
    private int capacityWeight(List<ResolvedDownloadItem> items) {
        long total = 0;
        for (ResolvedDownloadItem item : items) {
            if (item.expectedSizeBytes() == null || item.expectedSizeBytes() < 0) {
                return properties.jobConcurrency();
            }
            if (Long.MAX_VALUE - total < item.expectedSizeBytes()) {
                return properties.jobConcurrency();
            }
            total += item.expectedSizeBytes();
            if (total > properties.largeJobThreshold().toBytes()) {
                return properties.jobConcurrency();
            }
        }
        return 1;
    }

    /** Adquiere la fase única de empaquetado de forma interrumpible. */
    private void acquirePackaging() {
        try {
            packagingSemaphore.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    /**
     * Consume una ventana de descargas directamente hacia el ZIP y elimina cada temporal.
     */
    private ArchiveOutcome writeArchive(
            DownloadJobRequestedEvent event,
            List<FailedDownload> resolutionFailures,
            DownloadPipeline pipeline,
            ArchiveBuilder.ArchiveWriter writer,
            Path jobDirectory) throws IOException {
        List<DownloadedArtifact> downloaded = new ArrayList<>();
        List<FailedDownload> failed = new ArrayList<>(resolutionFailures);
        while (pipeline.hasNext()) {
            DownloadAttempt attempt = pipeline.next();
            if (cancellations.cancelled(event.payload().jobId())) {
                throw new CancellationException("download_job_cancelled");
            }
            if (attempt.artifact() != null) {
                DownloadedArtifact artifact = attempt.artifact();
                try {
                    writer.add(artifact.filename(), artifact.path());
                    downloaded.add(artifact);
                    publishProgress(
                            event, clock.instant(), artifact.itemId(), "COMPLETED",
                            artifact.sizeBytes(), artifact.sizeBytes(), artifact.sha256(), null);
                } finally {
                    deleteTemporary(artifact.path());
                    metrics.temporaryRemoved(artifact.sizeBytes());
                }
            } else {
                failed.add(attempt.failure());
                publishProgress(
                        event, clock.instant(), attempt.failure().itemId(), "FAILED",
                        0, null, null, attempt.failure().errorCode());
            }
        }

        Map<UUID, DownloadItemMetadata> failedMetadata = failedMetadata(event, failed);
        ManualShortcuts shortcuts = writeManualShortcuts(failed, failedMetadata, jobDirectory);
        if (downloaded.isEmpty() && shortcuts.entries().isEmpty()) {
            throw new AllDownloadsFailedException(failed.size());
        }
        for (ArchiveEntry entry : shortcuts.entries()) {
            writer.add(entry.path(), entry.source());
        }
        String status = downloaded.isEmpty()
                ? "MANUAL_ONLY"
                : failed.isEmpty() ? "READY" : "PARTIAL";
        byte[] manifest = manifest(
                event, status, downloaded, failed, failedMetadata, shortcuts.pathsByItem());
        writer.add("manifest.json", manifest);
        return new ArchiveOutcome(status, downloaded.size(), failed.size(), manifest);
    }

    /**
     * Ventana incremental que mantiene como máximo cuatro descargas en vuelo por trabajo.
     */
    private final class DownloadPipeline {
        private final DownloadJobRequestedEvent event;
        private final List<ResolvedDownloadItem> items;
        private final Path jobDirectory;
        private final int window;
        private final CompletionService<DownloadAttempt> completions = new ExecutorCompletionService<>(executor);
        private final List<Future<DownloadAttempt>> futures = new ArrayList<>();
        private final DownloadBudget budget = new DownloadBudget(properties.maxTotalSize().toBytes());
        private final Set<String> usedNames = filenamePolicy.newNameSet();
        private int submitted;
        private int completed;

        /** Inicializa y precarga solamente la primera ventana. */
        private DownloadPipeline(
                DownloadJobRequestedEvent event,
                List<ResolvedDownloadItem> items,
                Path jobDirectory,
                int window) {
            this.event = event;
            this.items = items;
            this.jobDirectory = jobDirectory;
            this.window = Math.min(window, items.size());
            while (submitted < this.window) {
                submitNext();
            }
        }

        /** @return Si queda algún resultado por consumir. */
        private boolean hasNext() {
            return completed < items.size();
        }

        /** Obtiene el siguiente resultado y repone una única plaza. */
        private DownloadAttempt next() {
            DownloadAttempt attempt = awaitCompleted(completions);
            completed++;
            if (submitted < items.size()) {
                submitNext();
            }
            return attempt;
        }

        /** Envía una única descarga al ejecutor global. */
        private void submitNext() {
            ResolvedDownloadItem item = items.get(submitted++);
            publishProgress(
                    event, clock.instant(), item.itemId(), "DOWNLOADING",
                    0, item.expectedSizeBytes(), null, null);
            try {
                futures.add(completions.submit(() -> downloadOne(
                        event, item, jobDirectory, budget, usedNames)));
                cancellations.track(event.payload().jobId(), futures);
            } catch (RejectedExecutionException exception) {
                throw new InfrastructureException("download_executor_saturated", exception);
            }
        }
    }

    /** Descarga una fuente ya resuelta dentro del presupuesto global del trabajo. */
    private DownloadAttempt downloadOne(
            DownloadJobRequestedEvent event,
            ResolvedDownloadItem resolved,
            Path jobDirectory,
            DownloadBudget totalBudget,
            Set<String> usedNames) {
        String filename;
        synchronized (usedNames) {
            filename = filenamePolicy.filenameFor(resolved, usedNames);
        }
        Path target = jobDirectory.resolve("files").resolve(filename);
        try {
            try (TemporaryDiskCapacity.Lease disk =
                    diskCapacity.reserve(jobDirectory, resolved.expectedSizeBytes())) {
                metrics.downloadStarted();
                try {
                    DownloadedArtifact artifact = remoteDownloader.download(
                            resolved,
                            filename,
                            target,
                            totalBudget,
                            properties.maxFileSize().toBytes());
                    disk.completed();
                    metrics.temporaryAdded(artifact.sizeBytes());
                    return DownloadAttempt.success(artifact);
                } finally {
                    metrics.downloadFinished();
                }
            }
        } catch (DownloadRejectedException exception) {
            return DownloadAttempt.failure(new FailedDownload(
                    resolved.itemId(), resolved.appId(), resolved.sourceRef(), filename, exception.code()));
        } catch (RuntimeException exception) {
            deleteTemporary(target);
            if (cancellations.cancelled(event.payload().jobId())) {
                throw new CancellationException("download_job_cancelled");
            }
            throw exception;
        }
    }

    /** Espera un resultado y preserva su causa original. */
    private <T> T awaitCompleted(CompletionService<T> completions) {
        try {
            return await(completions.take());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    /** Extrae el resultado de un futuro. */
    private <T> T await(Future<T> future) {
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

    /** Recupera metadatos internos únicamente para los elementos fallidos. */
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

    /** Crea accesos directos seguros para descargas manuales. */
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

    /** Valida que una URL manual sea pública y no incluya secretos. */
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

    /** Detecta parámetros de consulta que podrían contener credenciales. */
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

    /** Serializa el manifiesto v2 que también se inserta dentro del ZIP. */
    private byte[] manifest(
            DownloadJobRequestedEvent event,
            String status,
            List<DownloadedArtifact> downloaded,
            List<FailedDownload> failed,
            Map<UUID, DownloadItemMetadata> failedMetadata,
            Map<UUID, String> manualShortcutPaths) {
        Map<UUID, ManifestItem> items = new HashMap<>();
        for (DownloadedArtifact artifact : downloaded) {
            items.put(artifact.itemId(), new ManifestItem(
                    artifact.itemId(), artifact.appId(), artifact.sourceRef(), null,
                    artifact.filename(), "COMPLETED", artifact.sizeBytes(), artifact.sha256(),
                    artifact.filename(), null, null, null));
        }
        for (FailedDownload failure : failed) {
            DownloadItemMetadata metadata = failedMetadata.get(failure.itemId());
            String shortcut = manualShortcutPaths.get(failure.itemId());
            items.put(failure.itemId(), new ManifestItem(
                    failure.itemId(), failure.appId(), failure.sourceRef(),
                    metadata == null ? failure.appId().toString() : metadata.appName(),
                    failure.filename(), "FAILED", null, null,
                    shortcut, null, failure.errorCode(), shortcut));
        }
        List<ManifestItem> ordered = event.payload().items().stream()
                .map(item -> items.get(item.itemId()))
                .toList();
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new DownloadManifest(
                    MANIFEST_VERSION, event.payload().jobId(), clock.instant(), status, ordered));
        } catch (IOException exception) {
            throw new InfrastructureException("manifest_creation_failed", exception);
        }
    }

    /** Publica el evento terminal después de confirmar ZIP y manifiesto. */
    private void publishReadyEvent(
            DownloadJobRequestedEvent event,
            ArchiveOutcome outcome,
            StoredArtifact zip,
            String zipObjectKey) {
        Instant occurredAt = clock.instant();
        Duration ttl = storageProperties.presignedUrlTtl().compareTo(Duration.ofDays(7)) > 0
                ? Duration.ofDays(7)
                : storageProperties.presignedUrlTtl();
        DownloadReadyPayload payload = new DownloadReadyPayload(
                event.payload().jobId(),
                outcome.status(),
                zipObjectKey,
                zip.sizeBytes(),
                zip.sha256(),
                outcome.successfulItems(),
                outcome.failedItems(),
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

    /** Publica un cambio de estado de item conservando el contrato v1. */
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

    /** Publica un fallo terminal del trabajo. */
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

    /** Construye identificadores estables para que el inbox pueda deduplicar reintentos. */
    private UUID deterministicEventId(UUID jobId, String type, String discriminator) {
        return UUID.nameUUIDFromBytes(
                (jobId + ":" + type + ":" + discriminator).getBytes(StandardCharsets.UTF_8));
    }

    /** Crea un directorio temporal aislado para el trabajo. */
    private Path createJobDirectory(UUID jobId) {
        try {
            Path base = Path.of(properties.tempDirectory());
            Files.createDirectories(base);
            return Files.createTempDirectory(base, jobId + "-");
        } catch (IOException exception) {
            throw new InfrastructureException("temp_directory_creation_failed", exception);
        }
    }

    /** Elimina un temporal individual sin ocultar el resultado principal. */
    private void deleteTemporary(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            LOGGER.debug("Could not delete temporary download path {}", path, exception);
        }
    }

    /** Elimina un objeto de almacenamiento como compensación. */
    private void deleteStoredObject(String objectKey) {
        try {
            artifactStore.delete(objectKey);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not delete incomplete object {}", objectKey, exception);
        }
    }

    /** Calcula los bytes que todavía quedan en un directorio temporal. */
    private long directorySize(Path root) {
        if (root == null || !Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0;
                }
            }).sum();
        } catch (IOException exception) {
            return 0;
        }
    }

    /** Elimina el árbol temporal del trabajo. */
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

    /** Resultado de una resolución individual. */
    private record ResolutionAttempt(ResolvedDownloadItem resolved, FailedDownload failure) {
        private static ResolutionAttempt success(ResolvedDownloadItem resolved) {
            return new ResolutionAttempt(resolved, null);
        }

        private static ResolutionAttempt failure(FailedDownload failure) {
            return new ResolutionAttempt(null, failure);
        }
    }

    /** Resultado de la fase de resolución. */
    private record PreparedDownloads(
            List<ResolvedDownloadItem> resolved,
            List<FailedDownload> failed) {}

    /** Resultado de una descarga individual. */
    private record DownloadAttempt(DownloadedArtifact artifact, FailedDownload failure) {
        private static DownloadAttempt success(DownloadedArtifact artifact) {
            return new DownloadAttempt(artifact, null);
        }

        private static DownloadAttempt failure(FailedDownload failure) {
            return new DownloadAttempt(null, failure);
        }
    }

    /** Accesos manuales y sus rutas dentro del ZIP. */
    private record ManualShortcuts(
            List<ArchiveEntry> entries,
            Map<UUID, String> pathsByItem) {}

    /** Datos terminales calculados al cerrar el ZIP. */
    private record ArchiveOutcome(
            String status,
            int successfulItems,
            int failedItems,
            byte[] manifest) {}

    /** Señala que no existe ningún contenido útil para el usuario. */
    private static final class AllDownloadsFailedException extends RuntimeException {
        private final int failedItems;

        private AllDownloadsFailedException(int failedItems) {
            super("all_downloads_failed");
            this.failedItems = Math.max(1, failedItems);
        }

        private int failedItems() {
            return failedItems;
        }
    }
}
