package es.ubu.batchdownloader.downloadworker.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/** Verifica el reparto ponderado y justo de trabajos. */
class JobCapacityTest {
    @Test
    void reservesBothPermitsForAnExclusiveJobAndReturnsThemOnce() {
        JobCapacity capacity = new JobCapacity(2, new SimpleMeterRegistry());

        assertThat(capacity.fair()).isTrue();
        try (JobCapacity.Lease ignored = capacity.acquire(2)) {
            assertThat(capacity.availablePermits()).isZero();
        }
        assertThat(capacity.availablePermits()).isEqualTo(2);
    }
}
