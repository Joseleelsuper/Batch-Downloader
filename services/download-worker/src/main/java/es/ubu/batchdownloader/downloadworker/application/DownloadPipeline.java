package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Mantiene una ventana acotada de transferencias y entrega sus resultados en orden de finalización,
 * compartiendo presupuesto de bytes y nombres únicos del trabajo.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipelineFactory
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Procesamiento de descargas
 */
public final class DownloadPipeline {
    private final DownloadJobRequestedEvent event;
    private final List<ResolvedDownloadItem> items;
    private final Path jobDirectory;
    private final int window;
    private final RemoteDownloader remoteDownloader;
    private final FilenamePolicy filenamePolicy;
    private final DownloadProperties properties;
    private final DownloadCancellationRegistry cancellations;
    private final DownloadWorkerMetrics metrics;
    private final DownloadEventEmitter events;
    private final Clock clock;
    private final DownloadJobFiles files;
    private final CompletionService<Attempt> completions;
    private final List<Future<Attempt>> futures = new ArrayList<>();
    private final DownloadBudget budget;
    private final Set<String> usedNames;
    private int submitted;
    private int completed;

    /**
     * Conecta los colaboradores del trabajo, crea su presupuesto y conjunto de nombres y lanza las
     * primeras tareas hasta llenar la ventana.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param items Fuentes resueltas que conservan los identificadores de los elementos admitidos.
     * @param jobDirectory Directorio temporal exclusivo de esta ejecución del trabajo.
     * @param window Máximo de tareas simultáneas, acotado por el número de elementos.
     * @param executor Ejecutor compartido que ejecuta las tareas sin crear un pool por trabajo.
     * @param remoteDownloader Cadena de políticas de descarga, integridad, límites, reintentos y
     *     limpieza.
     * @param filenamePolicy Asigna nombres seguros y únicos dentro del archivo del trabajo.
     * @param properties Límites de cantidad, concurrencia, bytes y empaquetado del worker.
     * @param cancellations Registro que conecta las solicitudes de cancelación con los futuros del
     *     trabajo.
     * @param metrics Contadores y temporizadores de actividad, temporales y empaquetado.
     * @param events Publicador de progreso y resultados que conserva identidad determinista de
     *     eventos.
     * @param clock Reloj para fechar el progreso y las decisiones del coordinador.
     * @param files Creación y limpieza de temporales y compensación de objetos incompletos.
     */
    public DownloadPipeline(
            DownloadJobRequestedEvent event,
            List<ResolvedDownloadItem> items,
            Path jobDirectory,
            int window,
            ExecutorService executor,
            RemoteDownloader remoteDownloader,
            FilenamePolicy filenamePolicy,
            DownloadProperties properties,
            DownloadCancellationRegistry cancellations,
            DownloadWorkerMetrics metrics,
            DownloadEventEmitter events,
            Clock clock,
            DownloadJobFiles files) {
        this.event = event;
        this.items = items;
        this.jobDirectory = jobDirectory;
        this.window = Math.min(window, items.size());
        this.remoteDownloader = remoteDownloader;
        this.filenamePolicy = filenamePolicy;
        this.properties = properties;
        this.cancellations = cancellations;
        this.metrics = metrics;
        this.events = events;
        this.clock = clock;
        this.files = files;
        this.completions = new ExecutorCompletionService<>(executor);
        this.budget = new DownloadBudget(properties.maxTotalSize().toBytes());
        this.usedNames = filenamePolicy.newNameSet();
        while (submitted < this.window) {
            submitNext();
        }
    }

    /**
     * Indica si quedan resultados por recoger de la selección resuelta.
     *
     * @return true mientras no se haya consumido un resultado por cada fuente.
     */
    boolean hasNext() {
        return completed < items.size();
    }

    /**
     * Espera el siguiente resultado completado y ocupa el hueco de la ventana con otra descarga
     * pendiente antes de devolverlo.
     *
     * @return éxito o rechazo del próximo elemento terminado.
     */
    Attempt next() {
        Attempt attempt = awaitCompleted();
        completed++;
        if (submitted < items.size()) {
            submitNext();
        }
        return attempt;
    }

    /**
     * Publica estado DOWNLOADING, envía una tarea al ejecutor y registra todos los futuros para
     * cancelación.
     *
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si el
     *     ejecutor no admite la tarea.
     */
    private void submitNext() {
        ResolvedDownloadItem item = items.get(submitted++);
        events.progress(
                event, clock.instant(), item.itemId(), "DOWNLOADING",
                0, item.expectedSizeBytes(), null, null);
        try {
            futures.add(completions.submit(() -> downloadOne(item)));
            cancellations.track(event.payload().jobId(), futures);
        } catch (RejectedExecutionException exception) {
            throw new InfrastructureException("download_executor_saturated", exception);
        }
    }

    /**
     * Reserva un nombre único y descarga con límites e integridad. Convierte rechazos funcionales
     * en resultados individuales; ante otros fallos intenta limpiar el temporal y conserva la
     * cancelación.
     *
     * @param resolved Instalador cuya resolución terminó correctamente.
     * @return artefacto completo o rechazo individual.
     * @throws java.util.concurrent.CancellationException si el trabajo se canceló al fallar la
     *     transferencia.
     */
    private Attempt downloadOne(ResolvedDownloadItem resolved) {
        String filename;
        synchronized (usedNames) {
            filename = filenamePolicy.filenameFor(resolved, usedNames);
        }
        Path target = jobDirectory.resolve("files").resolve(filename);
        try {
            metrics.downloadStarted();
            try {
                DownloadedArtifact artifact = remoteDownloader.download(
                        resolved,
                        filename,
                        target,
                        budget,
                        properties.maxFileSize().toBytes());
                metrics.temporaryAdded(artifact.sizeBytes());
                return Attempt.success(artifact);
            } finally {
                metrics.downloadFinished();
            }
        } catch (DownloadRejectedException exception) {
            return Attempt.failure(new FailedDownload(
                    resolved.itemId(), resolved.appId(), resolved.sourceRef(), filename, exception.code()));
        } catch (RuntimeException exception) {
            files.deleteTemporary(target);
            if (cancellations.cancelled(event.payload().jobId())) {
                throw new CancellationException("download_job_cancelled");
            }
            throw exception;
        }
    }

    /**
     * Espera una finalización de la cola de tareas sin imponer el orden de entrada.
     *
     * @return resultado de la siguiente transferencia terminada.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si se
     *     interrumpe la espera; conserva la interrupción del hilo.
     */
    private Attempt awaitCompleted() {
        try {
            return await(completions.take());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    /**
     * Recupera el resultado y propaga directamente fallos RuntimeException de la tarea; envuelve
     * otros fallos y conserva interrupciones.
     *
     * @param future Tarea cuyo resultado o fallo se recupera después de terminar.
     * @return resultado completado de la transferencia.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si se
     *     interrumpe la espera o la tarea falla con una causa comprobada.
     */
    private Attempt await(Future<Attempt> future) {
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

    /**
     * Representa éxito o rechazo individual conservando solo uno de los dos resultados en las
     * factorías del pipeline.
     *
     * @param artifact Instalador descargado e íntegro; null en un intento fallido.
     * @param failure Rechazo individual del instalador; null en un intento satisfactorio.
     * @since 0.1.0
     * @version 0.1.0
     * @category Procesamiento de descargas
     */
    record Attempt(DownloadedArtifact artifact, FailedDownload failure) {
        /**
         * Construye el resultado de una transferencia completa sin rechazo asociado.
         *
         * @param artifact Instalador descargado e íntegro; null en un intento fallido.
         * @return intento con artefacto y fallo null.
         */
        private static Attempt success(DownloadedArtifact artifact) {
            return new Attempt(artifact, null);
        }

        /**
         * Construye el resultado individual de un instalador rechazado.
         *
         * @param failure Rechazo individual del instalador; null en un intento satisfactorio.
         * @return intento con fallo y artefacto null.
         */
        private static Attempt failure(FailedDownload failure) {
            return new Attempt(null, failure);
        }
    }
}
