package es.ubu.batchdownloader.downloadworker.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Registra la presión interna del pipeline sin desplegar otro servicio. */
@Component
public class DownloadWorkerMetrics {
    /** Descargas HTTP activas. */
    private final AtomicInteger activeDownloads = new AtomicInteger();
    /** Bytes temporales todavía presentes. */
    private final AtomicLong temporaryBytes = new AtomicLong();
    /** ZIP que se están construyendo o subiendo. */
    private final AtomicInteger activePackagings = new AtomicInteger();
    /** Espera para entrar en la única fase de empaquetado. */
    private final Timer packagingWait;
    /** Registro usado para iniciar las muestras. */
    private final MeterRegistry registry;

    /** Inicializa y publica los medidores. */
    public DownloadWorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("download_worker_active_downloads", activeDownloads);
        registry.gauge("download_worker_temporary_bytes", temporaryBytes);
        registry.gauge("download_worker_active_packagings", activePackagings);
        packagingWait = registry.timer("download_worker_packaging_wait");
    }

    /** Señala el inicio de una descarga. */
    public void downloadStarted() {
        activeDownloads.incrementAndGet();
    }

    /** Señala el final de una descarga. */
    public void downloadFinished() {
        activeDownloads.decrementAndGet();
    }

    /** Añade bytes temporales recién descargados. */
    public void temporaryAdded(long bytes) {
        temporaryBytes.addAndGet(Math.max(0, bytes));
    }

    /** Retira bytes temporales ya incorporados o limpiados. */
    public void temporaryRemoved(long bytes) {
        temporaryBytes.updateAndGet(current -> Math.max(0, current - Math.max(0, bytes)));
    }

    /** Inicia la medición de espera de empaquetado. */
    public Timer.Sample startPackagingWait() {
        return Timer.start(registry);
    }

    /** Finaliza la medición de espera de empaquetado. */
    public void stopPackagingWait(Timer.Sample sample) {
        sample.stop(packagingWait);
    }

    /** Registra cuánto permaneció el comando en RabbitMQ antes de ser atendido. */
    public void queueWait(Duration duration) {
        registry.timer("download_worker_queue_wait").record(duration);
    }

    /** Señala la entrada en la fase exclusiva de ZIP/subida. */
    public void packagingStarted() {
        activePackagings.incrementAndGet();
    }

    /** Señala la salida de la fase exclusiva de ZIP/subida. */
    public void packagingFinished() {
        activePackagings.decrementAndGet();
    }

    /** Cuenta aplazamientos por un conjunto acotado de motivos estables. */
    public void capacityDeferred(String reason) {
        registry.counter("download_worker_capacity_deferred", "reason", reason).increment();
    }
}
