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
 * Conecta solicitudes de cancelación por trabajo con sus tareas en vuelo y conserva temporalmente
 * las cancelaciones que llegan antes de registrar las tareas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipeline
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Capacidad y coordinación de descargas
 */
@Component
public class DownloadCancellationRegistry {
    /**
     * Valor de configuración que limita r e t e n t i o n y evita esperas indefinidas.
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
     * Registra el instante de cancelación y solicita interrupción de todos los futuros actualmente
     * asociados al trabajo.
     *
     * @param jobId UUID del trabajo cuya cancelación o actividad se registra.
     */
    public void cancel(UUID jobId) {
        cancellations.put(jobId, Instant.now());
        activeTasks.getOrDefault(jobId, List.of()).forEach(task -> task.cancel(true));
    }

    /**
     * Consulta si existe una marca de cancelación vigente para el trabajo.
     *
     * @param jobId UUID del trabajo cuya cancelación o actividad se registra.
     * @return true cuando la marca todavía está registrada.
     */
    public boolean cancelled(UUID jobId) {
        return cancellations.containsKey(jobId);
    }

    /**
     * Sustituye la lista de tareas por una copia y las cancela inmediatamente si la solicitud llegó
     * antes del registro.
     *
     * @param jobId UUID del trabajo cuya cancelación o actividad se registra.
     * @param tasks Futuros actuales del trabajo; se copia la lista antes de registrarla.
     */
    public void track(UUID jobId, List<? extends Future<?>> tasks) {
        List<Future<?>> copy = List.copyOf(tasks);
        activeTasks.put(jobId, copy);
        if (cancelled(jobId)) {
            copy.forEach(task -> task.cancel(true));
        }
    }

    /**
     * Retira tareas y marca de cancelación cuando el coordinador termina de gestionar el trabajo.
     *
     * @param jobId UUID del trabajo cuya cancelación o actividad se registra.
     */
    public void finish(UUID jobId) {
        activeTasks.remove(jobId);
        cancellations.remove(jobId);
    }

    /**
     * Retira las marcas de cancelación anteriores al corte de retención para acotar su permanencia
     * en memoria.
     */
    @Scheduled(fixedDelay = 300_000)
    void expireUnclaimedCancellations() {
        Instant cutoff = Instant.now().minus(RETENTION);
        cancellations.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }
}
