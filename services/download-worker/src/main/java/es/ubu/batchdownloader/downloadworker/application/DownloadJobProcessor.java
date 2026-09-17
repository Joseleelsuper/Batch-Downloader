package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore.StoredArtifact;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Coordina resolución, reservas, descarga y empaquetado del trabajo. Materializa las transferencias
 * antes de ocupar una plaza de ZIP y publica el resultado solo tras almacenar ZIP y manifiesto,
 * compensando objetos incompletos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadResolutionService
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipelineFactory
 * @see es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder
 * @see es.ubu.batchdownloader.downloadworker.ports.ArtifactStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Procesamiento de descargas
 */
@Service
public class DownloadJobProcessor {
    private final DownloadPipelineFactory pipelines;
    private final ArtifactStore artifactStore;
    private final ArchiveBuilder archiveBuilder;
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
    private final LinuxInstallerBundleWriter linuxInstaller;
    @Value("${app.download.linux-installer-enabled:true}")
    private boolean linuxInstallerEnabled = true;
    /** Resolución acotada de fuentes antes de descargar. */
    private final DownloadResolutionService resolutions;

    /**
     * Conecta las fases y políticas ya configuradas sin construir adaptadores ni ejecutores dentro
     * del procesador.
     *
     * @param pipelines Factoría configurada que abre ventanas con colaboradores compartidos y
     *     presupuesto propio.
     * @param artifactStore Almacenamiento de ZIP y manifiesto con integridad de los bytes escritos.
     * @param archiveBuilder Constructor que escribe el ZIP sobre el flujo proporcionado por
     *     almacenamiento.
     * @param properties Límites de cantidad, concurrencia, bytes y empaquetado del worker.
     * @param clock Reloj para fechar el progreso y las decisiones del coordinador.
     * @param cancellations Registro que conecta las solicitudes de cancelación con los futuros del
     *     trabajo.
     * @param jobCapacity Admisión justa de trabajos normales y exclusivos.
     * @param packagingSemaphore Permisos compartidos para limitar los ZIP que se comprimen
     *     simultáneamente.
     * @param metrics Contadores y temporizadores de actividad, temporales y empaquetado.
     * @param diskCapacity Reservas y comprobaciones de espacio del volumen temporal.
     * @param artifactCapacity Cuota lógica de almacenamiento; null omite esta comprobación en
     *     composiciones de prueba.
     * @param events Publicador de progreso y resultados que conserva identidad determinista de
     *     eventos.
     * @param files Creación y limpieza de temporales y compensación de objetos incompletos.
     * @param manualShortcuts Generador de alternativas manuales para elementos sin instalador
     *     entregable.
     * @param manifests Generador del manifiesto ordenado por la selección original.
     * @param linuxInstaller Generador del runtime offline y su configuración de instalación Linux.
     * @param resolutions Validación inicial, resolución y estimación de peso y bytes del trabajo.
     */
    public DownloadJobProcessor(
            DownloadPipelineFactory pipelines,
            ArtifactStore artifactStore,
            ArchiveBuilder archiveBuilder,
            DownloadProperties properties,
            Clock clock,
            DownloadCancellationRegistry cancellations,
            JobCapacity jobCapacity,
            @Qualifier("packagingSemaphore") Semaphore packagingSemaphore,
            DownloadWorkerMetrics metrics,
            TemporaryDiskCapacity diskCapacity,
            ArtifactCapacity artifactCapacity,
            DownloadEventEmitter events,
            DownloadJobFiles files,
            ManualShortcutWriter manualShortcuts,
            DownloadManifestWriter manifests,
            LinuxInstallerBundleWriter linuxInstaller,
            DownloadResolutionService resolutions) {
        this.pipelines = pipelines;
        this.artifactStore = artifactStore;
        this.archiveBuilder = archiveBuilder;
        this.properties = properties;
        this.clock = clock;
        this.cancellations = cancellations;
        this.jobCapacity = jobCapacity;
        this.packagingSemaphore = packagingSemaphore;
        this.metrics = metrics;
        this.diskCapacity = diskCapacity;
        this.artifactCapacity = artifactCapacity;
        this.events = events;
        this.files = files;
        this.manualShortcuts = manualShortcuts;
        this.manifests = manifests;
        this.linuxInstaller = linuxInstaller;
        this.resolutions = resolutions;
    }

    /**
     * Valida la selección, reserva capacidad, completa descargas y alternativas manuales y
     * transmite el ZIP al almacén. Publica éxito, fallo total o aplazamiento; en todas las salidas
     * libera reservas y temporales e intenta retirar objetos sin resultado confirmado.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @throws es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException si no hay
     *     capacidad segura; publica antes una espera de treinta segundos cuando el transporte lo
     *     permite.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si una fase
     *     de infraestructura impide completar el trabajo y debe actuar la política del consumidor.
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
                    DownloadPipeline pipeline = pipelines.create(
                            event, prepared.resolved(), activeDirectory, window);
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

    /**
     * Añade el mayor margen entre un MiB y el uno por ciento de la descarga para reservar cabeceras
     * y manifiesto del ZIP.
     *
     * @param downloadedBytes Estimación del conjunto de instaladores en bytes antes de comprimir.
     * @return estimación con margen; máximo total configurado si desborda la suma.
     */
    private long artifactEstimate(long downloadedBytes) {
        long overhead = Math.max(1024L * 1024, downloadedBytes / 100);
        try {
            return Math.addExact(downloadedBytes, overhead);
        } catch (ArithmeticException exception) {
            return properties.maxTotalSize().toBytes();
        }
    }

    /**
     * Espera una plaza de empaquetado en intervalos de 250 ms y revisa cancelación mientras no
     * consigue el permiso.
     *
     * @param jobId UUID del trabajo cuya cancelación se comprueba durante la espera.
     * @throws java.util.concurrent.CancellationException si se cancela el trabajo durante la
     *     espera.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si se
     *     interrumpe el hilo; conserva la interrupción.
     */
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
     * Recoge todas las descargas en orden de finalización, publica sus estados y prepara
     * alternativas manuales, manifiesto y runtime Linux antes de ocupar una plaza de ZIP.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param resolutionFailures Rechazos de resolución ya conocidos, incluidos los elementos
     *     manuales.
     * @param pipeline Cursor de descargas que entrega cada resultado al terminar.
     * @param jobDirectory Directorio temporal exclusivo de esta ejecución del trabajo.
     * @return entradas listas para empaquetar sin nuevas consultas HTTP.
     * @throws DownloadJobProcessor.AllDownloadsFailedException si no queda instalador ni
     *     alternativa manual que entregar.
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
        var installerEntries = linuxInstallerEnabled
                ? linuxInstaller.write(event.payload().jobId(), downloaded, manifest)
                : java.util.Map.<String, byte[]>of();
        if (!installerEntries.isEmpty()) metrics.linuxInstallerCreated();
        return new ArchivePreparation(
                status, List.copyOf(downloaded), List.copyOf(failed), shortcuts, manifest, installerEntries);
    }

    /**
     * Copia los instaladores completos al ZIP, intenta borrar cada temporal tras su copia y añade
     * manifiesto, accesos manuales y runtime con permisos ejecutables donde corresponde.
     *
     * @param preparation Archivos locales y entradas pequeñas preparados antes de adquirir la plaza
     *     de ZIP.
     * @param writer Escritor válido durante el callback del archivo abierto.
     * @return estado y recuentos del archivo junto al manifiesto que se almacenará por separado.
     * @throws java.io.IOException si falla la escritura de una entrada del archivo.
     */
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
        for (var entry : preparation.installerEntries().entrySet()) {
            if (entry.getKey().endsWith(".sh") || entry.getKey().equals("bin/batch-linux-installer")) {
                writer.addExecutable(entry.getKey(), entry.getValue());
            } else {
                writer.add(entry.getKey(), entry.getValue());
            }
        }
        return new ArchiveOutcome(
                preparation.status(),
                preparation.downloaded().size(),
                preparation.failed().size(),
                preparation.manifest());
    }

    /**
     * Reúne todos los contenidos y el resultado del trabajo antes de adquirir el permiso de
     * empaquetado.
     *
     * @param status Resultado conjunto READY, PARTIAL o MANUAL_ONLY que se incluirá en el
     *     manifiesto.
     * @param downloaded Instaladores descargados y verificados que se copiarán al ZIP.
     * @param failed Elementos que no terminaron con un instalador descargado.
     * @param shortcuts Entradas manuales y sus metadatos y asociaciones con elementos.
     * @param manifest Bytes del manifiesto preparado que se escriben tanto dentro como fuera del
     *     ZIP.
     * @param installerEntries Entradas del runtime Linux ya materializadas en memoria; vacío cuando
     *     no se incluye.
     * @since 0.1.0
     * @version 0.1.0
     * @category Procesamiento de descargas
     */
    private record ArchivePreparation(
            String status,
            List<DownloadedArtifact> downloaded,
            List<FailedDownload> failed,
            ManualShortcutWriter.Result shortcuts,
            byte[] manifest,
            java.util.Map<String, byte[]> installerEntries) {}

    /**
     * Devuelve el resultado calculado al escribir las entradas y el manifiesto que acompaña al ZIP.
     *
     * @param status Resultado conjunto READY, PARTIAL o MANUAL_ONLY que se incluirá en el
     *     manifiesto.
     * @param successfulItems Cantidad de instaladores descargados incluidos en el ZIP.
     * @param failedItems Cantidad de elementos fallidos; la excepción de fallo total la acota a un
     *     mínimo de uno.
     * @param manifest Bytes del manifiesto preparado que se escriben tanto dentro como fuera del
     *     ZIP.
     * @since 0.1.0
     * @version 0.1.0
     * @category Procesamiento de descargas
     */
    private record ArchiveOutcome(
            String status,
            int successfulItems,
            int failedItems,
            byte[] manifest) {}

    /**
     * Señala que el trabajo no produjo instaladores ni accesos manuales y evita publicar un ZIP sin
     * contenido útil.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Procesamiento de descargas
     */
    private static final class AllDownloadsFailedException extends RuntimeException {
        /**
         * Número positivo de elementos fallidos.
         */
        private final int failedItems;

        /**
         * Conserva un recuento de fallo de al menos un elemento para construir el evento terminal.
         *
         * @param failedItems Cantidad de elementos fallidos; la excepción de fallo total la acota a
         *     un mínimo de uno.
         */
        private AllDownloadsFailedException(int failedItems) {
            super("all_downloads_failed");
            this.failedItems = Math.max(1, failedItems);
        }

        /**
         * Recupera el recuento que debe comunicarse cuando no existe contenido entregable.
         *
         * @return número positivo de elementos fallidos.
         */
        private int failedItems() {
            return failedItems;
        }
    }
}
