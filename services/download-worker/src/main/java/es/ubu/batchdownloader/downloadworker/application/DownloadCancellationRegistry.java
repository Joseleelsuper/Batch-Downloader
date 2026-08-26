package es.ubu.batchdownloader.downloadworker.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Implementa el componente {@code DownloadCancellationRegistry}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class DownloadCancellationRegistry {
    /**
     * Constante que define {@code RETENTION}.
     */
    private static final Duration RETENTION = Duration.ofMinutes(30);

    /**
     * Estado {@code cancellations} mantenido por {@code DownloadCancellationRegistry}.
     */
    private final ConcurrentHashMap<UUID, Instant> cancellations = new ConcurrentHashMap<>();
    /**
     * Estado {@code activeTasks} mantenido por {@code DownloadCancellationRegistry}.
     */
    private final ConcurrentHashMap<UUID, List<Future<?>>> activeTasks = new ConcurrentHashMap<>();

    /**
     * Indica si puede realizarse la operación mediante {@code cancel}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     */
    public void cancel(UUID jobId) {
        cancellations.put(jobId, Instant.now());
        activeTasks.getOrDefault(jobId, List.of()).forEach(task -> task.cancel(true));
    }

    /**
     * Indica si puede realizarse la operación mediante {@code cancelled}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    public boolean cancelled(UUID jobId) {
        return cancellations.containsKey(jobId);
    }

    /**
     * Ejecuta la operación {@code track}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param tasks Valor de {@code tasks} utilizado por la operación.
     */
    public void track(UUID jobId, List<? extends Future<?>> tasks) {
        List<Future<?>> copy = List.copyOf(tasks);
        activeTasks.put(jobId, copy);
        if (cancelled(jobId)) {
            copy.forEach(task -> task.cancel(true));
        }
    }

    /**
     * Ejecuta la operación {@code finish}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     */
    public void finish(UUID jobId) {
        activeTasks.remove(jobId);
        cancellations.remove(jobId);
    }

    /**
     * Ejecuta la operación {@code expireUnclaimedCancellations}.
     */
    @Scheduled(fixedDelay = 300_000)
    void expireUnclaimedCancellations() {
        Instant cutoff = Instant.now().minus(RETENTION);
        cancellations.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }
}
