package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadJobRequestedEvent;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.FailedDownload;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Comprueba la selección y resuelve sus fuentes con concurrencia acotada antes de reservar
 * capacidad de descarga; separa rechazos individuales y calcula reservas defensivas.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Procesamiento de descargas
 */
public final class DownloadResolutionService {
    private final SourceReferenceResolver resolver;
    private final ExecutorService executor;
    private final DownloadProperties properties;
    private final DownloadCancellationRegistry cancellations;
    private final DownloadEventEmitter events;
    private final Clock clock;

    /**
     * Conecta resolución exacta, ejecutor, límites, cancelación y publicación de progreso.
     *
     * @param resolver Puerto que obtiene y revalida la URI de la fuente exacta seleccionada.
     * @param executor Ejecutor compartido que ejecuta las tareas sin crear un pool por trabajo.
     * @param properties Límites de cantidad, concurrencia, bytes y empaquetado del worker.
     * @param cancellations Registro que conecta las solicitudes de cancelación con los futuros del
     *     trabajo.
     * @param events Publicador de progreso y resultados que conserva identidad determinista de
     *     eventos.
     * @param clock Reloj para fechar el progreso y las decisiones del coordinador.
     */
    public DownloadResolutionService(
            SourceReferenceResolver resolver,
            ExecutorService executor,
            DownloadProperties properties,
            DownloadCancellationRegistry cancellations,
            DownloadEventEmitter events,
            Clock clock) {
        this.resolver = resolver;
        this.executor = executor;
        this.properties = properties;
        this.cancellations = cancellations;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Rechaza cantidades superiores al máximo y UUID de elemento repetidos antes de comenzar la
     * resolución.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @return too_many_items, duplicate_item_id o null si supera ambas comprobaciones.
     */
    String invalidReason(DownloadJobRequestedEvent event) {
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
     * Resuelve la selección con una ventana por trabajo, recoge finalizaciones y publica rechazos
     * individuales; cancela las tareas registradas si se solicita parada.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @return copias de fuentes resueltas y rechazos en sus órdenes de finalización.
     * @throws java.util.concurrent.CancellationException si se cancela el trabajo durante la
     *     resolución.
     */
    PreparedDownloads resolve(DownloadJobRequestedEvent event) {
        List<DownloadItemRequest> items = event.payload().items();
        CompletionService<ResolutionAttempt> completions = new ExecutorCompletionService<>(executor);
        List<Future<ResolutionAttempt>> futures = new ArrayList<>();
        int next = 0;
        int completed = 0;
        int window = Math.min(properties.perJobConcurrency(), items.size());
        while (next < window) {
            submit(event, items.get(next++), completions, futures);
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
                events.progress(
                        event, clock.instant(), attempt.failure().itemId(), "FAILED",
                        0, null, null, attempt.failure().errorCode());
            }
            if (next < items.size()) {
                submit(event, items.get(next++), completions, futures);
            }
            if (cancellations.cancelled(event.payload().jobId())) {
                futures.forEach(future -> future.cancel(true));
                throw new CancellationException("download_job_cancelled");
            }
        }
        return new PreparedDownloads(List.copyOf(resolved), List.copyOf(failed));
    }

    /**
     * Asigna todos los permisos a tamaños desconocidos, sumas desbordadas o trabajos que superan el
     * umbral grande; los demás consumen un permiso.
     *
     * @param items Fuentes resueltas que conservan los identificadores de los elementos admitidos.
     * @return uno para trabajo normal o la capacidad completa configurada.
     */
    int capacityWeight(List<ResolvedDownloadItem> items) {
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

    /**
     * Suma tamaños conocidos hasta el máximo total; usa ese máximo como reserva defensiva ante
     * tamaños desconocidos, negativos o desbordamiento.
     *
     * @param items Fuentes resueltas que conservan los identificadores de los elementos admitidos.
     * @return estimación acotada del conjunto en bytes.
     */
    long estimatedBytes(List<ResolvedDownloadItem> items) {
        long maximum = properties.maxTotalSize().toBytes();
        long total = 0;
        for (ResolvedDownloadItem item : items) {
            Long expected = item.expectedSizeBytes();
            if (expected == null || expected < 0) return maximum;
            try {
                total = Math.addExact(total, expected);
            } catch (ArithmeticException exception) {
                return maximum;
            }
            if (total >= maximum) return maximum;
        }
        return total;
    }

    /**
     * Publica RESOLVING, envía una tarea y actualiza los futuros que deben recibir cancelación.
     *
     * @param event Solicitud validada con identidad del trabajo, selección exacta y correlación de
     *     eventos.
     * @param item Elemento admitido, posiblemente manual cuando no tiene sourceRef.
     * @param completions Cola que entrega tareas en su orden real de finalización.
     * @param futures Lista mutable de tareas que se registra para cancelación cooperativa.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si el
     *     ejecutor rechaza una nueva tarea.
     */
    private void submit(
            DownloadJobRequestedEvent event,
            DownloadItemRequest item,
            CompletionService<ResolutionAttempt> completions,
            List<Future<ResolutionAttempt>> futures) {
        events.progress(event, clock.instant(), item.itemId(), "RESOLVING", 0, null, null, null);
        try {
            futures.add(completions.submit(() -> resolveOne(item)));
            cancellations.track(event.payload().jobId(), futures);
        } catch (RejectedExecutionException exception) {
            throw new InfrastructureException("download_executor_saturated", exception);
        }
    }

    /**
     * Conserva los elementos sin sourceRef como descarga manual sin consultar el resolutor y
     * convierte sus rechazos en fallos individuales de la fuente exacta.
     *
     * @param item Elemento admitido, posiblemente manual cuando no tiene sourceRef.
     * @return fuente resuelta o rechazo con identidad del elemento.
     */
    private ResolutionAttempt resolveOne(DownloadItemRequest item) {
        if (item.sourceRef() == null) {
            return ResolutionAttempt.failure(new FailedDownload(
                    item.itemId(), item.appId(), null,
                    "manual-" + item.itemId() + ".url", "manual_download_required"));
        }
        try {
            return ResolutionAttempt.success(resolver.resolve(item));
        } catch (DownloadRejectedException exception) {
            return ResolutionAttempt.failure(new FailedDownload(
                    item.itemId(), item.appId(), item.sourceRef(),
                    "installer-" + item.itemId() + ".bin", exception.code()));
        }
    }

    /**
     * Espera la próxima tarea terminada de la cola de resolución y conserva la interrupción del
     * hilo.
     *
     * @param completions Cola que entrega tareas en su orden real de finalización.
     * @param <T> Tipo de resultado de las tareas registradas en la cola.
     * @return resultado de la tarea terminada.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si se
     *     interrumpe la espera.
     */
    private <T> T awaitCompleted(CompletionService<T> completions) {
        try {
            return await(completions.take());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    /**
     * Desenvuelve la finalización de una tarea, propagando su fallo de ejecución cuando ya es
     * RuntimeException.
     *
     * @param future Tarea cuyo resultado o fallo se recupera después de terminar.
     * @param <T> Tipo de resultado producido por el futuro.
     * @return resultado completado.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si se
     *     interrumpe la espera o la causa requiere envolverse.
     */
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

    /**
     * Separa las fuentes que pueden descargarse de los rechazos que todavía pueden ofrecer una
     * alternativa manual.
     *
     * @param resolved Fuentes exactas resueltas en orden de finalización.
     * @param failed Rechazos de resolución en orden de finalización.
     * @since 0.1.0
     * @version 0.1.0
     * @category Procesamiento de descargas
     */
    record PreparedDownloads(
            List<ResolvedDownloadItem> resolved,
            List<FailedDownload> failed) {}

    /**
     * Representa el resultado de resolver un elemento sin perder identidad ni motivo de rechazo.
     *
     * @param resolved Instalador cuya resolución terminó correctamente.
     * @param failure Rechazo individual del instalador; null en un intento satisfactorio.
     * @since 0.1.0
     * @version 0.1.0
     * @category Procesamiento de descargas
     */
    private record ResolutionAttempt(ResolvedDownloadItem resolved, FailedDownload failure) {
        /**
         * Conserva una fuente resuelta sin fallo asociado.
         *
         * @param resolved Instalador cuya resolución terminó correctamente.
         * @return intento con resolución y fallo null.
         */
        private static ResolutionAttempt success(ResolvedDownloadItem resolved) {
            return new ResolutionAttempt(resolved, null);
        }

        /**
         * Conserva un rechazo individual de resolución sin una URI de descarga.
         *
         * @param failure Rechazo individual del instalador; null en un intento satisfactorio.
         * @return intento con fallo y resolución null.
         */
        private static ResolutionAttempt failure(FailedDownload failure) {
            return new ResolutionAttempt(null, failure);
        }
    }
}
