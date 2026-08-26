package es.ubu.batchdownloader.notification.operations;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** Verifica las señales operativas del consumidor de notificaciones. */
class NotificationWorkerHeartbeatTest {
    private static final Instant START = Instant.parse("2026-08-24T18:00:00Z");

    @Test
    void distinguishesTransientFailurePersistentFailureAndRecovery() {
        MutableClock clock = new MutableClock(START);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationWorkerHeartbeat heartbeat =
                new NotificationWorkerHeartbeat(clock, registry, Duration.ofMinutes(1));

        assertThat(heartbeat.health().getStatus()).isEqualTo(Status.UP);
        heartbeat.failure(new IllegalStateException("mail unavailable"));
        heartbeat.failure(null);
        assertThat(heartbeat.health().getStatus()).isEqualTo(Status.UP);
        heartbeat.failure(new IllegalArgumentException("invalid event"));

        Health failed = heartbeat.health();
        assertThat(failed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(failed.getDetails())
                .containsEntry("lastErrorType", "IllegalArgumentException")
                .containsEntry("consecutiveFailures", 3);
        assertThat(registry.find("notification.worker.heartbeat.age.seconds").gauge()).isNotNull();
        assertThat(registry.find("notification.worker.heartbeat.consecutive.failures").gauge())
                .isNotNull();

        clock.advance(Duration.ofSeconds(1));
        heartbeat.success();
        Health recovered = heartbeat.health();
        assertThat(recovered.getStatus()).isEqualTo(Status.UP);
        assertThat(recovered.getDetails())
                .containsEntry("lastSuccessAt", clock.instant().toString())
                .containsEntry("consecutiveFailures", 0);
    }

    @Test
    void staleHeartbeatDegradesUntilTheNextPulse() {
        MutableClock clock = new MutableClock(START);
        NotificationWorkerHeartbeat heartbeat = new NotificationWorkerHeartbeat(
                clock,
                new SimpleMeterRegistry(),
                Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(31));
        Health stale = heartbeat.health();
        assertThat(stale.getStatus()).isEqualTo(Status.DOWN);
        assertThat(stale.getDetails())
                .containsEntry("heartbeatAgeSeconds", 31.0)
                .containsEntry("lastSuccessAt", "never")
                .containsEntry("lastErrorAt", "never")
                .containsEntry("lastErrorType", "none");

        heartbeat.pulse();
        assertThat(heartbeat.health().getStatus()).isEqualTo(Status.UP);
    }

    /** Reloj controlable para verificar antigüedad sin usar esperas reales. */
    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
