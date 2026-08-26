package es.ubu.batchdownloader.contracts.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Prueba el umbral de degradación sin depender del reloj del sistema. */
class WorkerHeartbeatStateTest {

    @Test
    void transientFailureDoesNotDegradeButPersistentFailuresDo() {
        WorkerHeartbeatState state = new WorkerHeartbeatState(
                Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));

        state.failure(new IllegalStateException("detail-not-retained"));
        assertThat(state.degraded(Duration.ofMinutes(1), 3)).isFalse();
        state.failure(new IllegalStateException("detail-not-retained"));
        state.failure(new IllegalArgumentException("detail-not-retained"));

        assertThat(state.degraded(Duration.ofMinutes(1), 3)).isTrue();
        assertThat(state.snapshot().errorType()).isEqualTo("IllegalArgumentException");
        state.success();
        assertThat(state.degraded(Duration.ofMinutes(1), 3)).isFalse();
        assertThat(state.snapshot().consecutiveFailures()).isZero();
    }
}
