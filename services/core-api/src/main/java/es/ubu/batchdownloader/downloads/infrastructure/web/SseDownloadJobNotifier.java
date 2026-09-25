package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;
import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.lang.Nullable;

/**
 * Mantiene suscripciones SSE por trabajo hasta su purga, agrupando el progreso durante 250 ms.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobNotifications
 * @see es.ubu.batchdownloader.downloads.infrastructure.web.DownloadJobController
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Component
public class SseDownloadJobNotifier implements DownloadJobNotifier {
    /**
     * Valor de configuración que limita s s e  t i m e o u t  m i l l i s y evita esperas
     * indefinidas.
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
    private final DownloadJobStore jobs;
    private final ObjectProvider<DownloadStorageCoordinator> storage;
    /** Programa coalescing y heartbeats sin ocupar hilos HTTP. */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "download-sse");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Configura emisores y latidos periódicos; el registro de métricas, cuando existe, observa
     * conexiones activas.
     *
     * @param heartbeat Intervalo entre señales SSE; se limita por abajo a un segundo.
     * @param registry Registro opcional de métricas; null desactiva la instrumentación.
     */
    @Autowired
    public SseDownloadJobNotifier(
            @Value("${app.download.sse-heartbeat}") Duration heartbeat,
            @Nullable MeterRegistry registry,
            DownloadJobStore jobs,
            ObjectProvider<DownloadStorageCoordinator> storage) {
        this(heartbeat, () -> new SseEmitter(SSE_TIMEOUT_MILLIS), jobs, storage);
        if (registry != null) {
            registry.gauge(
                    "core_download_sse_connections_active",
                    this,
                    SseDownloadJobNotifier::activeConnections);
        }
    }

    /**
     * Configura emisores y latidos periódicos; el registro de métricas, cuando existe, observa
     * conexiones activas.
     *
     * @param heartbeat Intervalo entre señales SSE; se limita por abajo a un segundo.
     * @param emitterFactory Factoría de conexiones SSE, sustituible por emisores controlados en las
     *     pruebas.
     */
    SseDownloadJobNotifier(Duration heartbeat, Supplier<SseEmitter> emitterFactory,
            DownloadJobStore jobs, ObjectProvider<DownloadStorageCoordinator> storage) {
        this.emitterFactory = emitterFactory;
        this.jobs = jobs;
        this.storage = storage;
        long intervalMillis = Math.max(1_000, heartbeat.toMillis());
        scheduler.scheduleAtFixedRate(
                this::heartbeat, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Registra la conexión con limpieza en cierre, error o timeout y envía la vista inicial ya
     * autorizada.
     *
     * @param initial Vista ya autorizada del trabajo que se envía al abrir la suscripción.
     * @return emisor que permanece abierto durante la entrega y limpieza del archivo.
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
     * Sustituye el progreso pendiente por la vista más reciente; los estados terminales descartan
     * lo pendiente y se envían inmediatamente.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    @Override
    public void changed(DownloadJobView job) {
        if (job.status().terminal()) {
            pending.remove(job.id());
            scheduler.execute(() -> send(job));
            return;
        }
        pending.put(job.id(), job);
        schedule(job.id());
    }

    /**
     * Reserva como máximo un envío diferido por trabajo para agrupar cambios durante 250 ms.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     */
    private void schedule(UUID jobId) {
        if (scheduled.add(jobId)) {
            scheduler.schedule(() -> flush(jobId), 250, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Envía la última vista pendiente y vuelve a programar si llegó otra mientras se vaciaba el
     * registro.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     */
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

    /**
     * Difunde la misma vista a todas las conexiones actualmente registradas para el trabajo.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     */
    private void send(DownloadJobView job) {
        emitters.getOrDefault(job.id(), new CopyOnWriteArrayList<>())
                .forEach(emitter -> send(job, emitter));
    }

    /**
     * Envía un evento heartbeat con el instante actual a cada conexión y retira las que ya no
     * aceptan escritura.
     */
    void heartbeat() {
        emitters.forEach((jobId, jobEmitters) -> {
            try {
                var job = jobs.findById(jobId);
                if (job.isEmpty()) {
                    removed(jobId);
                    return;
                }
                DownloadStorageCoordinator coordinator = storage.getObject();
                coordinator.touch(jobId, "waiting", 0);
                send(coordinator.decorate(DownloadJobView.from(job.get())
                        .withLinuxContext(jobs.linuxContext(jobId))));
            } catch (RuntimeException exception) {
                // Un fallo temporal de lectura no debe cortar observadores todavía conectados.
            }
            jobEmitters.forEach(emitter -> {
                try {
                    emitter.send(SseEmitter.event().name("heartbeat").data(Instant.now().toString()));
                } catch (IOException | IllegalStateException exception) {
                    remove(jobId, emitter);
                }
            });
        });
    }

    /** Avisa solo después de la purga y cierra todas las conexiones del trabajo. */
    @Override
    public void removed(UUID jobId) {
        pending.remove(jobId);
        var jobEmitters = emitters.remove(jobId);
        if (jobEmitters == null) return;
        scheduler.execute(() -> {
            for (SseEmitter emitter : jobEmitters) {
                try {
                    emitter.send(SseEmitter.event().name("removed").data(jobId.toString()));
                } catch (IOException | IllegalStateException ignored) {
                    // Una desconexión previa no impide cerrar el resto de observadores.
                } finally {
                    emitter.complete();
                }
            }
        });
    }

    /**
     * Envía un evento job con identidad y vista y retira conexiones que fallan.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     * @param emitter Conexión SSE que recibe la vista o debe retirarse del registro.
     */
    private void send(DownloadJobView job, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("job").id(job.id().toString()).data(job));
        } catch (IOException | IllegalStateException exception) {
            remove(job.id(), emitter);
        }
    }

    /**
     * Retira la conexión y elimina la entrada del trabajo solo si su lista sigue siendo la misma y
     * queda vacía.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param emitter Conexión SSE que recibe la vista o debe retirarse del registro.
     */
    private void remove(UUID jobId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> jobEmitters = emitters.get(jobId);
        if (jobEmitters == null) return;
        jobEmitters.remove(emitter);
        if (jobEmitters.isEmpty()) emitters.remove(jobId, jobEmitters);
    }

    /**
     * Suma las conexiones registradas para exponer la ocupación actual del servicio SSE.
     *
     * @return número de emisores registrados, como valor de métrica.
     */
    private double activeConnections() {
        return emitters.values().stream().mapToInt(CopyOnWriteArrayList::size).sum();
    }

    /**
     * Detiene el planificador de latidos y envíos diferidos al destruir el componente.
     */
    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }
}
