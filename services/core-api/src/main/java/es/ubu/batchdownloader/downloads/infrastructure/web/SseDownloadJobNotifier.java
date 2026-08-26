package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.lang.Nullable;

/**
 * Implementa el componente {@code SseDownloadJobNotifier}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class SseDownloadJobNotifier implements DownloadJobNotifier {
    /**
     * Constante que define {@code SSE_TIMEOUT_MILLIS}.
     */
    private static final long SSE_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();
    /**
     * Estado {@code emitters} mantenido por {@code SseDownloadJobNotifier}.
     */
    private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    /** Último estado pendiente de cada trabajo. */
    private final ConcurrentHashMap<UUID, DownloadJobView> pending = new ConcurrentHashMap<>();
    /** Trabajos que ya tienen un envío diferido programado. */
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();
    /** Crea emisores; se inyecta en pruebas para observar el coalescing. */
    private final Supplier<SseEmitter> emitterFactory;
    /** Programa coalescing y heartbeats sin ocupar hilos HTTP. */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "download-sse");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Inicializa el heartbeat del canal SSE.
     *
     * @param heartbeat Intervalo entre señales de vida.
     */
    @Autowired
    public SseDownloadJobNotifier(
            @Value("${app.download.sse-heartbeat}") Duration heartbeat,
            @Nullable MeterRegistry registry) {
        this(heartbeat, () -> new SseEmitter(SSE_TIMEOUT_MILLIS));
        if (registry != null) {
            registry.gauge(
                    "core_download_sse_connections_active",
                    this,
                    SseDownloadJobNotifier::activeConnections);
        }
    }

    /** Conserva el constructor público anterior para usos embebidos. */
    public SseDownloadJobNotifier(Duration heartbeat) {
        this(heartbeat, () -> new SseEmitter(SSE_TIMEOUT_MILLIS));
    }

    /** Constructor acotado que permite observar los envíos sin abrir una conexión HTTP. */
    SseDownloadJobNotifier(Duration heartbeat, Supplier<SseEmitter> emitterFactory) {
        this.emitterFactory = emitterFactory;
        long intervalMillis = Math.max(1_000, heartbeat.toMillis());
        scheduler.scheduleAtFixedRate(
                this::heartbeat, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Ejecuta la operación {@code subscribe}.
     *
     * @param initial Valor de {@code initial} utilizado por la operación.
     * @return Resultado producido por {@code subscribe}.
     */
    public SseEmitter subscribe(DownloadJobView initial) {
        SseEmitter emitter = emitterFactory.get();
        CopyOnWriteArrayList<SseEmitter> jobEmitters = emitters.computeIfAbsent(
                initial.id(), ignored -> new CopyOnWriteArrayList<>());
        jobEmitters.add(emitter);
        Runnable cleanup = () -> remove(initial.id(), emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        send(initial, emitter);
        return emitter;
    }

    /**
     * Implementa {@code changed} para {@code SseDownloadJobNotifier}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     */
    @Override
    public void changed(DownloadJobView job) {
        if (job.status().terminal()) {
            pending.remove(job.id());
            send(job);
            return;
        }
        pending.put(job.id(), job);
        schedule(job.id());
    }

    /** Programa como máximo un envío cada 250 milisegundos por trabajo. */
    private void schedule(UUID jobId) {
        if (scheduled.add(jobId)) {
            scheduler.schedule(() -> flush(jobId), 250, TimeUnit.MILLISECONDS);
        }
    }

    /** Envía el último estado acumulado y rearma si llegó otro durante el envío. */
    private void flush(UUID jobId) {
        DownloadJobView job = pending.remove(jobId);
        if (job != null) {
            send(job);
        }
        scheduled.remove(jobId);
        if (pending.containsKey(jobId)) {
            schedule(jobId);
        }
    }

    /** Envía un estado a todos los suscriptores del trabajo. */
    private void send(DownloadJobView job) {
        emitters.getOrDefault(job.id(), new CopyOnWriteArrayList<>())
                .forEach(emitter -> send(job, emitter));
    }

    /** Mantiene abiertos los proxies y permite detectar conexiones rotas. */
    private void heartbeat() {
        emitters.forEach((jobId, jobEmitters) -> jobEmitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data(Instant.now().toString()));
            } catch (IOException | IllegalStateException exception) {
                remove(jobId, emitter);
            }
        }));
    }

    /**
     * Envía el contenido solicitado mediante {@code send}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     * @param emitter Valor de {@code emitter} utilizado por la operación.
     */
    private void send(DownloadJobView job, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("job").id(job.id().toString()).data(job));
            if (job.status().terminal()) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException exception) {
            remove(job.id(), emitter);
        }
    }

    /**
     * Elimina el recurso solicitado mediante {@code remove}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param emitter Valor de {@code emitter} utilizado por la operación.
     */
    private void remove(UUID jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters == null) return;
        jobEmitters.remove(emitter);
        if (jobEmitters.isEmpty()) emitters.remove(jobId, jobEmitters);
    }

    /** @return Número de conexiones SSE vivas, sin ocupar hilos HTTP inactivos. */
    private double activeConnections() {
        return emitters.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }

    /** Detiene el programador al cerrar la aplicación. */
    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }
}
