package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ArchiveEntry;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.ports.ArchiveBuilder;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore.StoredArtifact;
import es.ubu.batchdownloader.downloadworker.ports.JobStorageLedger;
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
    private final JobStorageLedger ledger;
    private final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.CompletableFuture<Void>> active =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, JobStorageReservation> reservations =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Semaphore packagingSemaphore;
    private final DownloadWorkerMetrics metrics;
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
     * @param ledger Reserva duradera y autorización del intento en Core.
     * @param packagingSemaphore Permisos compartidos para limitar los ZIP que se comprimen
     *     simultáneamente.
     * @param metrics Contadores y temporizadores de actividad, temporales y empaquetado.
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
            JobStorageLedger ledger,
            @Qualifier("packagingSemaphore") Semaphore packagingSemaphore,
            DownloadWorkerMetrics metrics,
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
        this.ledger = ledger;
        this.packagingSemaphore = packagingSemaphore;
        this.metrics = metrics;
        this.events = events;
        this.files = files;
        this.manualShortcuts = manualShortcuts;
        this.manifests = manifests;
        this.linuxInstaller = linuxInstaller;
        this.resolutions = resolutions;
    }

    /**
     * Valida la selección, reserva capacidad, completa descargas y alternativas manuales y
     * transmite el ZIP al almacén. La capacidad se libera solo tras borrado confirmado; un
     * resultado sellado se conserva para recuperarlo ante confirmación ambigua del broker.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si una fase
     *     de infraestructura impide completar el trabajo y debe actuar la política del consumidor.
     */
    public void process(DownloadJobRequestedEvent event) {
        UUID jobId = event.payload().jobId();
        var completion = new java.util.concurrent.CompletableFuture<Void>();
        java.util.concurrent.CompletableFuture<Void> existing;
        while ((existing = active.putIfAbsent(jobId, completion)) != null) existing.join();
        cancellations.writerStarted(jobId);
        metrics.jobStarted();
        try {
            processAttempt(event);
        } finally {
            cancellations.writerFinished(jobId);
            metrics.jobFinished();
            active.remove(jobId, completion);
            completion.complete(null);
        }
    }

    /** La respuesta de limpieza nunca precede a la terminación real de las escrituras. */
    public void clean(UUID jobId) {
        cancellations.cancel(jobId);
        var running = active.get(jobId);
        if (running != null) running.join();
        files.clean(jobId, true);
        events.clearReady(jobId);
    }

    public java.util.Map<UUID, Long> usage() { return files.usage(events.trackedJobs()); }
    public boolean active(UUID jobId) { return active.containsKey(jobId); }
    public java.util.Set<UUID> activeJobs() { return java.util.Set.copyOf(active.keySet()); }

    /** Mantiene el fence también cuando un proveedor todavía no ha enviado el siguiente bloque. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10_000)
    void heartbeatAttempts() {
        reservations.forEach((jobId, reservation) -> Thread.ofVirtual().start(() -> {
            try { reservation.heartbeat(); }
            catch (CancellationException cancelled) {
                // El fence se guarda en esta reserva; nunca cancela un intento posterior del mismo job.
            }
            catch (InfrastructureException unavailable) {
                // El siguiente bloque vuelve a comprobar Core y falla cerrado mientras siga ausente.
            }
        }));
    }

    private void processAttempt(DownloadJobRequestedEvent event) {
        if (event.occurredAt() != null && !event.occurredAt().isAfter(clock.instant())) {
            metrics.queueWait(Duration.between(event.occurredAt(), clock.instant()));
        }
        UUID jobId = event.payload().jobId();
        var pending = events.replayReady(event);
        if (pending != null) {
            ledger.update(jobId, event.eventId(), "READY", pending.storageBytes());
            return;
        }
        JobStorageLedger.State state = ledger.update(jobId, event.eventId(), "START", 0);
        if (!state.allowed() || state.cancelled()) return;
        events.rememberJob(event.eventId(), jobId);
        var volume = new AtomicReference<java.nio.file.FileStore>();
        JobStorageReservation reservation = new JobStorageReservation(ledger, jobId, event.eventId(), state,
                bytes -> {
                    try {
                        if (volume.get() == null) {
                            volume.set(java.nio.file.Files.getFileStore(Path.of(properties.tempDirectory())));
                        }
                        long free = volume.get().getUsableSpace();
                        if (free - bytes < properties.minFreeSpace().toBytes()) {
                            throw new CapacityDeferredException("temporary_storage_busy", null);
                        }
                    } catch (IOException exception) {
                        throw new CapacityDeferredException("temporary_storage_busy", exception);
                    }
                });
        String invalidReason = resolutions.invalidReason(event);
        if (invalidReason != null) {
            events.failed(event, invalidReason, event.payload().items().size());
            files.clean(jobId, true);
            events.clearReady(jobId);
            ledger.update(jobId, event.eventId(), "CLEANED", 0);
            return;
        }
        String prefix = "jobs/" + jobId;
        String manifestObjectKey = prefix + "/manifest.json";
        String zipObjectKey = prefix + "/bundle.zip";
        boolean sealed = false;
        boolean requeue = false;
        boolean finished = false;
        reservations.put(jobId, reservation);
        try {
            if (cancellations.cancelled(jobId)) {
                throw new CancellationException("download_job_cancelled");
            }
            // Recupera restos del mismo intento tras reiniciar, antes de generar nuevos bytes.
            files.clean(jobId, true);
            DownloadResolutionService.PreparedDownloads prepared = resolutions.resolve(event);
            if (cancellations.cancelled(jobId)) {
                throw new CancellationException("download_job_cancelled");
            }
            Path activeDirectory = files.createDirectory(jobId);
            DownloadBudget budget = new DownloadBudget(state.budgetBytes(), reservation::consume);
            ArchivePreparation preparation;
            try (DownloadPipeline pipeline = pipelines.create(
                    event, prepared.resolved(), activeDirectory, properties.perJobConcurrency(), budget)) {
                preparation = prepareArchive(event, prepared.failed(), pipeline, activeDirectory, budget);
            }
            Timer.Sample wait = metrics.startPackagingWait();
            try {
                acquirePackaging(jobId, reservation);
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
                                reservation.guard(output),
                                properties.zipLevel(),
                                writer -> outcomeReference.set(writeArchive(preparation, writer))));
                ArchiveOutcome outcome = outcomeReference.get();
                if (outcome == null) {
                    throw new InfrastructureException(
                            "zip_outcome_missing",
                            new IllegalStateException("Archive produced no result"));
                }
                if (cancellations.cancelled(jobId)) throw new CancellationException("download_job_cancelled");
                reservation.consume(outcome.manifest().length);
                artifactStore.putBytes(
                        manifestObjectKey,
                        outcome.manifest(),
                        "application/json",
                        properties.multipartPartSize().toBytes());
                if (cancellations.cancelled(jobId)) throw new CancellationException("download_job_cancelled");
                files.clean(jobId, false);
                // Sellado previo al recibo: un commit/confirm ambiguo nunca permite borrar el ZIP.
                sealed = true;
                events.ready(
                        event,
                        outcome.status(),
                        outcome.successfulItems(),
                        outcome.failedItems(),
                        storedZip,
                        zipObjectKey,
                        Math.addExact(storedZip.sizeBytes(), outcome.manifest().length));
                ledger.update(jobId, event.eventId(), "READY",
                        Math.addExact(storedZip.sizeBytes(), outcome.manifest().length));
            } finally {
                metrics.packagingFinished();
                packagingSemaphore.release();
            }
        } catch (AllDownloadsFailedException exception) {
            events.failed(event, "all_downloads_failed", exception.failedItems());
            finished = true;
        } catch (DownloadRejectedException exception) {
            events.failed(event, exception.code(), event.payload().items().size());
            finished = true;
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
            requeue = true;
        } catch (CancellationException exception) {
            events.failed(event, "download_job_cancelled", event.payload().items().size());
            finished = true;
        } finally {
            try {
                if (!sealed) {
                    files.clean(jobId, true);
                    events.clearReady(jobId);
                    if (requeue) ledger.update(jobId, event.eventId(), "REQUEUE", reservation.requiredBytes());
                    else if (finished) {
                        ledger.update(jobId, event.eventId(), "CLEANED", 0);
                    }
                }
            } finally {
                reservations.remove(jobId, reservation);
                cancellations.finish(jobId);
            }
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
    private void acquirePackaging(UUID jobId, JobStorageReservation reservation) {
        try {
            while (!packagingSemaphore.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                reservation.heartbeat();
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
            Path jobDirectory,
            DownloadBudget budget) {
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

        ManualShortcutWriter.Result shortcuts = manualShortcuts.write(event, failed, jobDirectory, budget);
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
