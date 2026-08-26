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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
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
    /** Cuota del bucket y reservas de ZIP todavía no visibles en MinIO. */
    private final ArtifactCapacity artifactCapacity;
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
    @Autowired
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
            TemporaryDiskCapacity diskCapacity,
            ArtifactCapacity artifactCapacity) {
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
        this.artifactCapacity = artifactCapacity;
        this.events = new DownloadEventEmitter(eventPublisher, storageProperties, clock);
        this.files = new DownloadJobFiles(artifactStore, metrics, properties);
        this.manualShortcuts = new ManualShortcutWriter(
                metadataLookup, filenamePolicy, publicHttpsUriPolicy);
        this.manifests = new DownloadManifestWriter(objectMapper, clock);
        this.resolutions = new DownloadResolutionService(
                sourceResolver, executor, properties, cancellations, events, clock);
    }

    /** Conserva el constructor previo para dobles unitarios sin cuota remota. */
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
            Semaphore packagingSemaphore,
            DownloadWorkerMetrics metrics,
            TemporaryDiskCapacity diskCapacity) {
        this(
                sourceResolver, metadataLookup, remoteDownloader, artifactStore, archiveBuilder,
                eventPublisher, filenamePolicy, publicHttpsUriPolicy, objectMapper, executor,
                properties, storageProperties, clock, cancellations, jobCapacity,
                packagingSemaphore, metrics, diskCapacity, null);
    }

    /**
     * Procesa un evento validado por el listener.
     *
     * @param event Solicitud de trabajo.
     */
    public void process(DownloadJobRequestedEvent event) {
        if (event.occurredAt() != null && !event.occurredAt().isAfter(clock.instant())) {
            metrics.queueWait(Duration.between(event.occurredAt(), clock.instant()));
        }
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
            try (JobCapacity.Lease ignored =
                    jobCapacity.acquire(weight, () -> cancellations.cancelled(jobId))) {
                jobDirectory = files.createDirectory(jobId);
                long estimatedBytes = resolutions.estimatedBytes(prepared.resolved());
                long artifactEstimate = artifactEstimate(estimatedBytes);
                try (TemporaryDiskCapacity.Lease diskLease =
                                diskCapacity.reserve(jobDirectory, estimatedBytes);
                        ArtifactCapacity.Lease ignoredArtifact = artifactCapacity == null
                                ? null
                                : artifactCapacity.reserve(artifactEstimate)) {
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
                            events,
                            clock,
                            files);
                    ArchivePreparation preparation = prepareArchive(
                            event, prepared.failed(), pipeline, activeDirectory);
                    // La promesa ya se ha materializado: el FileStore refleja ahora los bytes
                    // reales y la reserva estimada deja de contarlos por duplicado.
                    diskLease.completed();
                    Timer.Sample wait = metrics.startPackagingWait();
                    try {
                        acquirePackaging(jobId);
                    } finally {
                        metrics.stopPackagingWait(wait);
                    }
                    metrics.packagingStarted();
                    try {
                        AtomicReference<ArchiveOutcome> outcomeReference = new AtomicReference<>();
                        StoredArtifact storedZip = artifactStore.putStreaming(
                                zipObjectKey,
                                "application/zip",
                                properties.multipartPartSize().toBytes(),
                                output -> archiveBuilder.build(
                                        output,
                                        properties.zipLevel(),
                                        writer -> outcomeReference.set(writeArchive(preparation, writer))));
                        ArchiveOutcome outcome = outcomeReference.get();
                        if (outcome == null) {
                            throw new InfrastructureException(
                                    "zip_outcome_missing",
                                    new IllegalStateException("Archive produced no result"));
                        }
                        if (cancellations.cancelled(jobId)) return;
                        artifactStore.putBytes(
                                manifestObjectKey,
                                outcome.manifest(),
                                "application/json",
                                properties.multipartPartSize().toBytes());
                        if (cancellations.cancelled(jobId)) return;
                        events.ready(
                                event,
                                outcome.status(),
                                outcome.successfulItems(),
                                outcome.failedItems(),
                                storedZip,
                                zipObjectKey);
                        readyPublished = true;
                    } finally {
                        metrics.packagingFinished();
                        packagingSemaphore.release();
                    }
                }
            }
        } catch (AllDownloadsFailedException exception) {
            events.failed(event, "all_downloads_failed", exception.failedItems());
        } catch (CapacityDeferredException exception) {
            Instant retryAt = clock.instant().plus(Duration.ofSeconds(30));
            metrics.capacityDeferred(exception.reason());
            try {
                events.deferred(event, exception.reason(), retryAt);
            } catch (RuntimeException publishFailure) {
                // La espera sigue siendo no terminal aunque RabbitMQ no acepte el evento de UI;
                // el siguiente intento volverá a publicarlo sin consumir el presupuesto de fallo.
                exception.addSuppressed(publishFailure);
            }
            throw exception;
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

    /** Añade un uno por ciento y al menos un MiB para cabeceras ZIP y manifiesto. */
    private long artifactEstimate(long downloadedBytes) {
        long overhead = Math.max(1024L * 1024, downloadedBytes / 100);
        try {
            return Math.addExact(downloadedBytes, overhead);
        } catch (ArithmeticException exception) {
            return properties.maxTotalSize().toBytes();
        }
    }

    /** Adquiere la fase única de empaquetado de forma interrumpible. */
    private void acquirePackaging(UUID jobId) {
        try {
            while (!packagingSemaphore.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (cancellations.cancelled(jobId)) {
                    throw new CancellationException("download_job_cancelled");
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    /**
     * Consume una ventana de descargas directamente hacia el ZIP y elimina cada temporal.
     */
    private ArchivePreparation prepareArchive(
            DownloadJobRequestedEvent event,
            List<FailedDownload> resolutionFailures,
            DownloadPipeline pipeline,
            Path jobDirectory) {
        List<DownloadedArtifact> downloaded = new ArrayList<>();
        List<FailedDownload> failed = new ArrayList<>(resolutionFailures);
        while (pipeline.hasNext()) {
            DownloadPipeline.Attempt attempt = pipeline.next();
            if (cancellations.cancelled(event.payload().jobId())) {
                throw new CancellationException("download_job_cancelled");
            }
            if (attempt.artifact() != null) {
                DownloadedArtifact artifact = attempt.artifact();
                downloaded.add(artifact);
                events.progress(
                        event, clock.instant(), artifact.itemId(), "COMPLETED",
                        artifact.sizeBytes(), artifact.sizeBytes(), artifact.sha256(), null);
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
        String status = downloaded.isEmpty()
                ? "MANUAL_ONLY"
                : failed.isEmpty() ? "READY" : "PARTIAL";
        byte[] manifest = manifests.write(
                event, status, downloaded, failed, shortcuts.metadata(), shortcuts.pathsByItem());
        return new ArchivePreparation(
                status, List.copyOf(downloaded), List.copyOf(failed), shortcuts, manifest);
    }

    /** La fase de empaquetado no realiza accesos HTTP: solo consume temporales ya completos. */
    private ArchiveOutcome writeArchive(
            ArchivePreparation preparation,
            ArchiveBuilder.ArchiveWriter writer) throws IOException {
        for (DownloadedArtifact artifact : preparation.downloaded()) {
            try {
                writer.add(artifact.filename(), artifact.path());
            } finally {
                files.deleteTemporary(artifact.path());
                metrics.temporaryRemoved(artifact.sizeBytes());
            }
        }
        for (ArchiveEntry entry : preparation.shortcuts().entries()) {
            writer.add(entry.path(), entry.source());
        }
        writer.add("manifest.json", preparation.manifest());
        return new ArchiveOutcome(
                preparation.status(),
                preparation.downloaded().size(),
                preparation.failed().size(),
                preparation.manifest());
    }

    /** Entradas materializadas antes de reservar una plaza de ZIP. */
    private record ArchivePreparation(
            String status,
            List<DownloadedArtifact> downloaded,
            List<FailedDownload> failed,
            ManualShortcutWriter.Result shortcuts,
            byte[] manifest) {}

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
