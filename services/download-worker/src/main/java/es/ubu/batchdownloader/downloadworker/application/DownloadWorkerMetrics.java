package es.ubu.batchdownloader.downloadworker.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Registra actividad de descargas, temporales y empaquetado y los tiempos y motivos que explican
 * esperas del worker.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadPipeline
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Capacidad y coordinación de descargas
 */
@Component
public class DownloadWorkerMetrics {
    public enum Phase { RESOLUTION, TRANSFER, PACKAGING }
    public enum Outcome { SUCCESS, REJECTED, CANCELLED, FAILURE }
    /** Descargas HTTP activas. */
    private final AtomicInteger activeDownloads = new AtomicInteger();
    private final AtomicInteger activeJobs = new AtomicInteger();
    /** Bytes temporales todavía presentes. */
    private final AtomicLong temporaryBytes = new AtomicLong();
    /** ZIP que se están construyendo o subiendo. */
    private final AtomicInteger activePackagings = new AtomicInteger();
    /** Espera para acceder a una plaza de empaquetado. */
    private final Timer packagingWait;
    /** Registro usado para iniciar las muestras. */
    private final MeterRegistry registry;

    /**
     * Registra medidores compartidos y el temporizador de espera de empaquetado.
     *
     * @param registry Registro de ocupación, espera y resultados del worker.
     */
    public DownloadWorkerMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("download_worker_active_jobs", activeJobs);
        registry.gauge("download_worker_active_downloads", activeDownloads);
        registry.gauge("download_worker_temporary_bytes", temporaryBytes);
        registry.gauge("download_worker_active_packagings", activePackagings);
        packagingWait = registry.timer("download_worker_packaging_wait");
    }

    public void jobStarted() { activeJobs.incrementAndGet(); }
    public void jobFinished() { activeJobs.decrementAndGet(); }

    public Timer.Sample startPhase() { return Timer.start(registry); }

    public static Outcome outcome(Throwable failure) {
        if (failure instanceof CancellationException) return Outcome.CANCELLED;
        if (failure instanceof DownloadRejectedException) return Outcome.REJECTED;
        return Outcome.FAILURE;
    }

    public void stopPhase(Timer.Sample sample, Phase phase, Outcome outcome) {
        sample.stop(Timer.builder("download_worker_phase")
                .tag("phase", phase.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).register(registry));
    }

    public <T> T measurePhase(Phase phase, Supplier<T> operation) {
        Timer.Sample sample = startPhase();
        Outcome outcome = Outcome.SUCCESS;
        try { return operation.get(); }
        catch (RuntimeException | Error failure) { outcome = outcome(failure); throw failure; }
        finally { stopPhase(sample, phase, outcome); }
    }

    /**
     * Incrementa el número de transferencias actualmente activas.
     */
    public void downloadStarted() {
        activeDownloads.incrementAndGet();
    }

    /**
     * Decrementa la actividad al terminar la transferencia que previamente se registró.
     */
    public void downloadFinished() {
        activeDownloads.decrementAndGet();
    }

    /**
     * Añade bytes materializados al medidor de temporales; ignora cantidades negativas.
     *
     * @param bytes Cantidad de bytes que se reserva, contabiliza o consume según la operación.
     */
    public void temporaryAdded(long bytes) {
        temporaryBytes.addAndGet(Math.max(0, bytes));
    }

    /**
     * Descuenta bytes eliminados sin permitir que el medidor quede negativo.
     *
     * @param bytes Cantidad de bytes que se reserva, contabiliza o consume según la operación.
     */
    public void temporaryRemoved(long bytes) {
        temporaryBytes.updateAndGet(current -> Math.max(0, current - Math.max(0, bytes)));
    }

    /**
     * Inicia una medición cuando un trabajo empieza a esperar un permiso de ZIP.
     *
     * @return muestra que debe detenerse al terminar la espera.
     */
    public Timer.Sample startPackagingWait() {
        return Timer.start(registry);
    }

    /**
     * Finaliza la muestra y registra su duración en el temporizador de espera de ZIP.
     *
     * @param sample Medición iniciada cuando el trabajo empezó a esperar una plaza de empaquetado.
     */
    public void stopPackagingWait(Timer.Sample sample) {
        sample.stop(packagingWait);
    }

    /**
     * Registra cuánto esperó el evento en cola antes de procesarse.
     *
     * @param duration Tiempo que el evento pasó esperando antes de comenzar su ejecución.
     */
    public void queueWait(Duration duration) {
        registry.timer("download_worker_queue_wait").record(duration);
    }

    /**
     * Incrementa la cantidad de empaquetados activos.
     */
    public void packagingStarted() {
        activePackagings.incrementAndGet();
    }

    /**
     * Decrementa los empaquetados activos al abandonar la fase.
     */
    public void packagingFinished() {
        activePackagings.decrementAndGet();
    }

    /**
     * Cuenta un aplazamiento utilizando un código de motivo estable como etiqueta.
     *
     * @param reason Código que permite distinguir la causa de aplazamiento por capacidad.
     */
    public void capacityDeferred(String reason) {
        String boundedReason = "temporary_storage_busy".equals(reason) || "storage_budget_wait".equals(reason) ? reason : "other";
        registry.counter("download_worker_capacity_deferred", "reason", boundedReason).increment();
    }

    /**
     * Cuenta un archivo que incorpora el runtime de instalación Linux.
     */
    public void linuxInstallerCreated() {
        registry.counter("download_worker_linux_installer_created").increment();
    }
}
