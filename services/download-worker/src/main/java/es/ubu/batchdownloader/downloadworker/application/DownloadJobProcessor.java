package es.ubu.batchdownloader.downloadworker.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
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
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Descarga y empaqueta un trabajo con ventanas acotadas y subida directa a MinIO.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Service
public class DownloadJobProcessor {
    private final RemoteDownloader remoteDownloader;
    private final ArtifactStore artifactStore;
    private final ArchiveBuilder archiveBuilder;
    private final FilenamePolicy filenamePolicy;
    private final ExecutorService executor;
    private final DownloadProperties properties;
    private final Clock clock;
    private final DownloadCancellationRegistry cancellations;
    private final JobCapacity jobCapacity;
    private final Semaphore packagingSemaphore;
    private final DownloadWorkerMetrics metrics;
    /** Reserva global del SSD para los temporales en vuelo. */
    private final TemporaryDiskCapacity diskCapacity;
    /** Publicación de eventos separada de la orquestación. */
    private final DownloadEventEmitter events;
    /** Ciclo de vida aislado de archivos y objetos incompletos. */
    private final DownloadJobFiles files;
    /** Generación aislada de accesos manuales seguros. */
    private final ManualShortcutWriter manualShortcuts;
    /** Serialización aislada del manifiesto público. */
    private final DownloadManifestWriter manifests;
    /** Resolución acotada de fuentes antes de descargar. */
    private final DownloadResolutionService resolutions;

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
        this.remoteDownloader = remoteDownloader;
        this.artifactStore = artifactStore;
        this.archiveBuilder = archiveBuilder;
        this.filenamePolicy = filenamePolicy;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
        this.cancellations = cancellations;
        this.jobCapacity = jobCapacity;
        this.packagingSemaphore = packagingSemaphore;
        this.metrics = metrics;
        this.diskCapacity = diskCapacity;
        this.events = new DownloadEventEmitter(eventPublisher, storageProperties, clock);
        this.files = new DownloadJobFiles(artifactStore, metrics, properties);
        this.manualShortcuts = new ManualShortcutWriter(
                metadataLookup, filenamePolicy, publicHttpsUriPolicy);
        this.manifests = new DownloadManifestWriter(objectMapper, clock);
        this.resolutions = new DownloadResolutionService(
                sourceResolver, executor, properties, cancellations, events, clock);
    }

    /**
     * Procesa un evento validado por el listener.
     *
     * @param event Solicitud de trabajo.
     */
    public void process(DownloadJobRequestedEvent event) {
        String invalidReason = resolutions.invalidReason(event);
        if (invalidReason != null) {
            events.failed(event, invalidReason, event.payload().items().size());
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
            DownloadResolutionService.PreparedDownloads prepared = resolutions.resolve(event);
            if (cancellations.cancelled(jobId)) {
                return;
            }
            int weight = resolutions.capacityWeight(prepared.resolved());
            try (JobCapacity.Lease ignored = jobCapacity.acquire(weight)) {
                jobDirectory = files.createDirectory(jobId);
                try (TemporaryDiskCapacity.Lease ignoredDisk =
                        diskCapacity.reserve(jobDirectory, 0L)) {
                    // La adquisición comprueba la reserva mínima antes de iniciar el trabajo.
                }
                Path activeDirectory = jobDirectory;
                int window = weight > 1 ? 1 : properties.perJobConcurrency();
                DownloadPipeline pipeline = new DownloadPipeline(
                        event,
                        prepared.resolved(),
                        activeDirectory,
                        window,
                        executor,
                        remoteDownloader,
                        filenamePolicy,
                        properties,
                        cancellations,
                        metrics,
                        diskCapacity,
                        events,
                        clock,
                        files);
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
                    events.ready(
                            event,
                            outcome.status(),
                            outcome.successfulItems(),
                            outcome.failedItems(),
                            storedZip,
                            zipObjectKey);
                    readyPublished = true;
                } catch (AllDownloadsFailedException exception) {
                    events.failed(event, "all_downloads_failed", exception.failedItems());
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
                files.deleteStored(zipObjectKey);
                files.deleteStored(manifestObjectKey);
            }
            cancellations.finish(jobId);
            if (jobDirectory != null) {
                files.removeDirectory(jobDirectory);
            }
        }
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
            DownloadPipeline.Attempt attempt = pipeline.next();
            if (cancellations.cancelled(event.payload().jobId())) {
                throw new CancellationException("download_job_cancelled");
            }
            if (attempt.artifact() != null) {
                DownloadedArtifact artifact = attempt.artifact();
                try {
                    writer.add(artifact.filename(), artifact.path());
                    downloaded.add(artifact);
                    events.progress(
                            event, clock.instant(), artifact.itemId(), "COMPLETED",
                            artifact.sizeBytes(), artifact.sizeBytes(), artifact.sha256(), null);
                } finally {
                    files.deleteTemporary(artifact.path());
                    metrics.temporaryRemoved(artifact.sizeBytes());
                }
            } else {
                failed.add(attempt.failure());
                events.progress(
                        event, clock.instant(), attempt.failure().itemId(), "FAILED",
                        0, null, null, attempt.failure().errorCode());
            }
        }

        ManualShortcutWriter.Result shortcuts = manualShortcuts.write(event, failed, jobDirectory);
        if (downloaded.isEmpty() && shortcuts.entries().isEmpty()) {
            throw new AllDownloadsFailedException(failed.size());
        }
        for (ArchiveEntry entry : shortcuts.entries()) {
            writer.add(entry.path(), entry.source());
        }
        String status = downloaded.isEmpty()
                ? "MANUAL_ONLY"
                : failed.isEmpty() ? "READY" : "PARTIAL";
        byte[] manifest = manifests.write(
                event, status, downloaded, failed, shortcuts.metadata(), shortcuts.pathsByItem());
        writer.add("manifest.json", manifest);
        return new ArchiveOutcome(status, downloaded.size(), failed.size(), manifest);
    }

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
