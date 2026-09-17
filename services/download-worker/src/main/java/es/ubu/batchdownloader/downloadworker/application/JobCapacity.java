package es.ubu.batchdownloader.downloadworker.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Reparte permisos globales con un semáforo justo y pesos por trabajo, permitiendo reservar toda la
 * capacidad para descargas exclusivas.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadResolutionService
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadJobProcessor
 * @since 0.1.0
 * @version 0.1.0
 * @category Capacidad y coordinación de descargas
 */
public final class JobCapacity {
    /** Semáforo ponderado y justo. */
    private final Semaphore permits;
    /** Número de trabajos realmente activos. */
    private final AtomicInteger activeJobs = new AtomicInteger();
    /** Tiempo de espera por una plaza normal o por las ocho de un job exclusivo. */
    private final Timer capacityWait;

    /**
     * Crea el semáforo justo con la capacidad recibida y registra trabajos activos y duración de
     * espera.
     *
     * @param capacity Cantidad total de permisos que pueden repartirse entre trabajos.
     * @param registry Registro de ocupación, espera y resultados del worker.
     */
    public JobCapacity(int capacity, MeterRegistry registry) {
        this.permits = new Semaphore(capacity, true);
        registry.gauge("download_worker_active_jobs", activeJobs);
        this.capacityWait = registry.timer("download_worker_job_capacity_wait");
    }

    /**
     * Espera los permisos del trabajo sin una señal externa de cancelación, conservando la
     * posibilidad de interrupción del hilo.
     *
     * @param weight Número de permisos que requiere este trabajo; la resolución elige uno o toda la
     *     capacidad.
     * @return reserva de permisos que el coordinador debe cerrar.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si se
     *     interrumpe la espera; conserva la interrupción.
     */
    public Lease acquire(int weight) {
        return acquire(weight, () -> false);
    }

    /**
     * Espera permisos en intervalos de 250 ms, comprueba cancelación entre esperas y registra su
     * duración incluso si no llega a obtenerlos.
     *
     * @param weight Número de permisos que requiere este trabajo; la resolución elige uno o toda la
     *     capacidad.
     * @param cancelled Consulta de cancelación cooperativa revisada mientras el trabajo espera
     *     permisos.
     * @return reserva activa con el peso solicitado.
     * @throws java.util.concurrent.CancellationException si se solicita cancelación mientras
     *     espera.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si se
     *     interrumpe el hilo de espera; conserva la interrupción.
     */
    public Lease acquire(int weight, BooleanSupplier cancelled) {
        long startedAt = System.nanoTime();
        try {
            while (!permits.tryAcquire(weight, 250, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("download_job_cancelled");
                }
            }
            activeJobs.incrementAndGet();
            return new Lease(weight);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("download_job_interrupted", exception);
        } finally {
            capacityWait.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Expone la política de equidad del semáforo para verificar el orden de admisión.
     *
     * @return true si se respeta la política justa configurada.
     */
    boolean fair() {
        return permits.isFair();
    }

    /**
     * Consulta los permisos actualmente libres sin reservarlos.
     *
     * @return cantidad de permisos disponibles.
     */
    int availablePermits() {
        return permits.availablePermits();
    }

    /**
     * Conserva los permisos de un trabajo y devuelve su peso al cerrar la fase de ejecución.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Capacidad y coordinación de descargas
     */
    public final class Lease implements AutoCloseable {
        /** Peso que debe devolverse. */
        private final int weight;
        /** Impide liberar dos veces. */
        private boolean closed;

        /**
         * Registra el peso ya adquirido para devolver exactamente esos permisos.
         *
         * @param weight Número de permisos que requiere este trabajo; la resolución elige uno o
         *     toda la capacidad.
         */
        private Lease(int weight) {
            this.weight = weight;
        }

        /**
         * Devuelve los permisos y reduce el contador de trabajos activos una sola vez en el ciclo
         * de vida del coordinador.
         */
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
