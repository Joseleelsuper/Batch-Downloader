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

/** Limita cada hostname de origen a dos transferencias HTTP simultáneas. */
public final class HostLimitedRemoteDownloader implements RemoteDownloader {
    private final RemoteDownloader delegate;
    private final MeterRegistry registry;
    private final int limit;
    private final ConcurrentHashMap<String, HostState> hosts = new ConcurrentHashMap<>();

    /** Inicializa el límite por origen. */
    public HostLimitedRemoteDownloader(RemoteDownloader delegate, MeterRegistry registry, int limit) {
        this.delegate = delegate;
        this.registry = registry;
        this.limit = limit;
    }

    /** {@inheritDoc} */
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

    private record HostState(Semaphore permits, AtomicInteger active, Timer waitTimer) {}
}
