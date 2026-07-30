package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.downloads.application.DownloadJobView;
import es.ubu.batchdownloader.downloads.application.port.DownloadJobNotifier;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    /**
     * Ejecuta la operación {@code subscribe}.
     *
     * @param initial Valor de {@code initial} utilizado por la operación.
     * @return Resultado producido por {@code subscribe}.
     */
    public SseEmitter subscribe(DownloadJobView initial) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
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
        emitters.getOrDefault(job.id(), new CopyOnWriteArrayList<>())
                .forEach(emitter -> send(job, emitter));
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
}
