package es.ubu.batchdownloader.downloadworker.application;

import es.ubu.batchdownloader.downloadworker.config.StorageProperties;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import es.ubu.batchdownloader.downloadworker.ports.ArtifactStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Contabiliza por separado bytes persistidos y prometidos dentro de la cuota de MinIO. */
@Component
public final class ArtifactCapacity {
    private final ArtifactStore store;
    private final long quotaBytes;
    private final long admissionReserveBytes;
    private long reservedBytes;
    private long observedStoredBytes;

    /** Inicializa la cuota lógica y sus métricas sin realizar red en el constructor. */
    public ArtifactCapacity(
            ArtifactStore store,
            StorageProperties properties,
            DownloadProperties downloads,
            MeterRegistry registry) {
        this.store = store;
        this.quotaBytes = properties.quota().toBytes();
        long maximum = downloads.maxTotalSize().toBytes();
        this.admissionReserveBytes = Math.min(
                quotaBytes, maximum + Math.max(1024L * 1024, maximum / 100));
        registry.gauge("download_worker_artifact_reserved_bytes", this, value -> value.reserved());
        registry.gauge("download_worker_artifact_stored_bytes", this, value -> value.observed());
        registry.gauge("download_worker_artifact_quota_bytes", this, value -> value.quotaBytes);
    }

    /** Comprueba que la cuota tiene margen, usado por el endpoint interno de admisión. */
    public synchronized void requireAvailable() {
        reserveInternal(admissionReserveBytes);
    }

    /** Reserva el ZIP estimado antes de iniciar las descargas del trabajo. */
    public synchronized Lease reserve(long estimatedBytes) {
        long bytes = Math.max(0, estimatedBytes);
        reserveInternal(bytes);
        reservedBytes = Math.addExact(reservedBytes, bytes);
        return new Lease(bytes);
    }

    private void reserveInternal(long additionalBytes) {
        try {
            observedStoredBytes = store.usageBytes();
            long promised = Math.addExact(reservedBytes, additionalBytes);
            if (Math.addExact(observedStoredBytes, promised) > quotaBytes) {
                throw new CapacityDeferredException(
                        "artifact_quota_busy", new IllegalStateException("MinIO quota exhausted"));
            }
        } catch (CapacityDeferredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new CapacityDeferredException("artifact_capacity_unknown", exception);
        }
    }

    private synchronized double reserved() {
        return reservedBytes;
    }

    private synchronized double observed() {
        return observedStoredBytes;
    }

    /** Refresca el uso persistido aunque no entren nuevas solicitudes de admisión. */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void refreshUsage() {
        try {
            long current = store.usageBytes();
            synchronized (this) {
                observedStoredBytes = current;
            }
        } catch (RuntimeException ignored) {
            // El endpoint de capacidad seguirá fallando cerrado; la métrica conserva el
            // último valor confirmado en vez de publicar un cero engañoso.
        }
    }

    /** Reserva en vuelo liberable cuando el objeto ya figura como almacenado o se compensa. */
    public final class Lease implements AutoCloseable {
        private final long bytes;
        private boolean closed;

        private Lease(long bytes) {
            this.bytes = bytes;
        }

        @Override
        public void close() {
            synchronized (ArtifactCapacity.this) {
                if (!closed) {
                    closed = true;
                    reservedBytes = Math.max(0, reservedBytes - bytes);
                }
            }
        }
    }
}
