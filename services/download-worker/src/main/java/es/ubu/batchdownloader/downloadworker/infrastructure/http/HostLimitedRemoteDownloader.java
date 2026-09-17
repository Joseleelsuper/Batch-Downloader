package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.DownloadBudget;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadedArtifact;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Limita con un semáforo justo las descargas simultáneas que comparten host inicial y mide su
 * espera y actividad.
 *
 * @see es.ubu.batchdownloader.downloadworker.ports.RemoteDownloader
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
public final class HostLimitedRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;
    private final MeterRegistry registry;
    private final int limit;
    private final ConcurrentHashMap<String, HostState> hosts = new ConcurrentHashMap<>();

    /**
     * Conecta la siguiente política y el límite y métricas que se aplicarán por host.
     *
     * @param delegate Siguiente política o transporte de la cadena de descarga.
     * @param registry Registro de duraciones, concurrencia y reintentos.
     * @param limit Máximo de transferencias simultáneas por host inicial.
     */
    public HostLimitedRemoteDownloader(RemoteDownloader delegate, MeterRegistry registry, int limit) {
        this.delegate = delegate;
        this.registry = registry;
        this.limit = limit;
    }

    /**
     * Obtiene un permiso para el host inicial, mide la espera y devuelve el permiso al terminar la
     * llamada delegada, también si falla.
     *
     * @param item Elemento admitido o resuelto cuya fuente exacta se procesa.
     * @param filename Nombre seguro y deduplicado asignado al instalador descargado.
     * @param target Ruta local de destino del instalador.
     * @param totalBudget Presupuesto compartido de bytes del trabajo, consumido durante la
     *     transferencia.
     * @param maxFileBytes Límite máximo permitido para este archivo, en bytes.
     * @return artefacto devuelto por la siguiente política.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si se
     *     interrumpe la espera del permiso; conserva la interrupción.
     */
    @Override
    public DownloadedArtifact download(
            ResolvedDownloadItem item,
            String filename,
            Path target,
            DownloadBudget totalBudget,
            long maxFileBytes) {
        String host = item.url().getHost().toLowerCase(Locale.ROOT);
        HostState state = hosts.computeIfAbsent(host, this::newState);
        long startedAt = System.nanoTime();
        try {
            state.permits.acquire();
            state.waitTimer.record(
                    System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS);
            state.active.incrementAndGet();
            try {
                return delegate.download(item, filename, target, totalBudget, maxFileBytes);
            } finally {
                state.active.decrementAndGet();
                state.permits.release();
            }
        } catch (InterruptedException exception) {
            state.waitTimer.record(
                    System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS);
            Thread.currentThread().interrupt();
            throw new DownloadRejectedException("download_interrupted", exception);
        }
    }

    /**
     * Registra concurrencia y espera del host y crea su semáforo justo.
     *
     * @param host Nombre del host inicial normalizado a minúsculas.
     * @return estado compartido por las transferencias del mismo host.
     */
    private HostState newState(String host) {
        AtomicInteger active = new AtomicInteger();
        Timer wait = Timer.builder("download_worker_host_wait")
                .tag("host", host)
                .register(registry);
        HostState state = new HostState(new Semaphore(limit, true), active, wait);
        Gauge.builder("download_worker_host_active_downloads", state.active, AtomicInteger::get)
                .tag("host", host)
                .register(registry);
        return state;
    }

    /**
     * Agrupa permiso, actividad y tiempo de espera de un host para compartir su límite entre
     * descargas.
     *
     * @param permits Semáforo justo que limita las transferencias del host.
     * @param active Contador de transferencias activas del host.
     * @param waitTimer Temporizador de espera por un permiso de ese host.
     * @since 0.1.0
     * @version 0.1.0
     * @category Transporte de descargas
     */
    private record HostState(Semaphore permits, AtomicInteger active, Timer waitTimer) {}
}
