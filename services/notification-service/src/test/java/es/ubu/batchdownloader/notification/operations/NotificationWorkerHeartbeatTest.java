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

/**
 * Comprueba degradación y recuperación de salud mediante un reloj controlable.
 *
 * @see es.ubu.batchdownloader.notification.operations.NotificationWorkerHeartbeat
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class NotificationWorkerHeartbeatTest {
    private static final Instant START = Instant.parse("2026-08-24T18:00:00Z");

    /**
     * Comprueba que un fallo aislado no degrada la salud, una racha sí lo hace y un éxito permite
     * recuperarla.
     */
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

    /**
     * Comprueba que un latido antiguo degrada readiness y que un nuevo pulso restaura su vigencia.
     */
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

    /**
     * Permite avanzar el tiempo de las pruebas de salud sin esperar ni modificar el reloj del
     * sistema.
     *
     * @see es.ubu.batchdownloader.notification.operations.NotificationWorkerHeartbeat
     * @since 0.1.0
     * @version 0.1.0
     * @category Notificaciones
     */
    private static final class MutableClock extends Clock {
        private Instant current;

        /**
         * Fija el instante inicial del reloj controlado de la prueba.
         *
         * @param current Instante inicial del reloj simulado.
         */
        private MutableClock(Instant current) {
            this.current = current;
        }

        /**
         * Avanza el instante del reloj en la duración indicada para simular antigüedad del latido.
         *
         * @param duration Duración que se suma al instante de la prueba.
         */
        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        /**
         * Mantiene UTC como zona del reloj de prueba.
         *
         * @return zona UTC.
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * Conserva este reloj fijo de prueba al solicitar otra zona.
         *
         * @param zone Zona solicitada; este doble mantiene su comportamiento UTC.
         * @return la misma instancia, cuyo instante se controla desde la prueba.
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * Expone el instante controlado actualmente por la prueba.
         *
         * @return instante simulado.
         */
        @Override
        public Instant instant() {
            return current;
        }
    }
}
