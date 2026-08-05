package es.ubu.batchdownloader.downloadworker.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reparte de forma justa dos plazas entre trabajos normales y exclusivos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public final class JobCapacity {
    /** Semáforo ponderado y justo. */
    private final Semaphore permits;
    /** Número de trabajos realmente activos. */
    private final AtomicInteger activeJobs = new AtomicInteger();

    /**
     * Inicializa la capacidad global.
     *
     * @param capacity Número de trabajos normales simultáneos.
     * @param registry Registro de métricas.
     */
    public JobCapacity(int capacity, MeterRegistry registry) {
        this.permits = new Semaphore(capacity, true);
        registry.gauge("download_worker_active_jobs", activeJobs);
    }

    /**
     * Reserva una o todas las plazas.
     *
     * @param weight Plazas requeridas.
     * @return Reserva liberable mediante try-with-resources.
     */
    public Lease acquire(int weight) {
        try {
            permits.acquire(weight);
            activeJobs.incrementAndGet();
            return new Lease(weight);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        }
    }

    /** @return Si el semáforo respeta el orden de llegada. */
    boolean fair() {
        return permits.isFair();
    }

    /** @return Plazas libres, utilizado por las pruebas de capacidad. */
    int availablePermits() {
        return permits.availablePermits();
    }

    /** Reserva activa de plazas. */
    public final class Lease implements AutoCloseable {
        /** Peso que debe devolverse. */
        private final int weight;
        /** Impide liberar dos veces. */
        private boolean closed;

        /** Inicializa la reserva. */
        private Lease(int weight) {
            this.weight = weight;
        }

        /** Devuelve las plazas al conjunto global. */
        @Override
        public void close() {
            if (!closed) {
                closed = true;
                activeJobs.decrementAndGet();
                permits.release(weight);
            }
        }
    }
}
