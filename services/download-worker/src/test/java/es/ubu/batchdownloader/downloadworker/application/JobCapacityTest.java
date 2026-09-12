package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Comprueba reservas ponderadas y espera de trabajos normales y exclusivos sobre el semáforo justo.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.JobCapacity
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de procesamiento y capacidad
 */
class JobCapacityTest {
    /**
     * Reserva las dos plazas, comprueba que el semáforo es justo y no quedan permisos y verifica su
     * devolución al salir del ámbito.
     */
    @Test
    void reservesBothPermitsForAnExclusiveJobAndReturnsThemOnce() {
        JobCapacity capacity = new JobCapacity(2, new SimpleMeterRegistry());

        assertThat(capacity.fair()).isTrue();
        try (JobCapacity.Lease ignored = capacity.acquire(2)) {
            assertThat(capacity.availablePermits()).isZero();
        }
        assertThat(capacity.availablePermits()).isEqualTo(2);
    }

    /**
     * Ocupa ocho permisos y comprueba que el noveno trabajo espera hasta liberar uno; al cerrar
     * todas las reservas vuelve a haber ocho plazas.
     */
    @Test
    void keepsTheNinthNormalJobWaitingUntilOneOfEightFinishes() throws Exception {
        JobCapacity capacity = new JobCapacity(8, new SimpleMeterRegistry());
        List<JobCapacity.Lease> active = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            active.add(capacity.acquire(1));
        }
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<JobCapacity.Lease> ninth = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            return capacity.acquire(1);
        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(ninth.isDone()).isFalse();

        active.removeLast().close();
        try (JobCapacity.Lease ignored = ninth.get(2, TimeUnit.SECONDS)) {
            assertThat(capacity.availablePermits()).isZero();
        } finally {
            active.forEach(JobCapacity.Lease::close);
        }
        assertThat(capacity.availablePermits()).isEqualTo(8);
    }

    /**
     * Mantiene un trabajo normal activo y comprueba que otro de peso ocho espera hasta que se
     * devuelve ese permiso y luego ocupa toda la capacidad.
     */
    @Test
    void waitsForAllEightPermitsBeforeStartingAnExclusiveJob() throws Exception {
        JobCapacity capacity = new JobCapacity(8, new SimpleMeterRegistry());
        JobCapacity.Lease normal = capacity.acquire(1);
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<JobCapacity.Lease> exclusive = CompletableFuture.supplyAsync(() -> {
            started.countDown();
            return capacity.acquire(8);
        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(exclusive.isDone()).isFalse();

        normal.close();
        try (JobCapacity.Lease ignored = exclusive.get(2, TimeUnit.SECONDS)) {
            assertThat(capacity.availablePermits()).isZero();
        }
        assertThat(capacity.availablePermits()).isEqualTo(8);
    }
}
