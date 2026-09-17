package es.ubu.batchdownloader.downloadworker.operations;

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
 * Verifica degradación por fallos consecutivos o latido antiguo y recuperación con tiempo
 * controlado.
 *
 * @see es.ubu.batchdownloader.downloadworker.operations.DownloadWorkerHeartbeat
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de integración y mensajería
 */
class DownloadWorkerHeartbeatTest {
    private static final Instant START = Instant.parse("2026-08-24T18:00:00Z");

    /**
     * Acumula tres fallos y comprueba estado DOWN y sus detalles; después registra éxito y exige
     * estado UP y contador cero.
     */
    @Test
    void degradesAfterThreeFailuresAndRecoversAfterSuccess() {
        MutableClock clock = new MutableClock(START);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DownloadWorkerHeartbeat heartbeat =
                new DownloadWorkerHeartbeat(clock, registry, Duration.ofMinutes(1));

        Health initial = heartbeat.health();
        assertThat(initial.getStatus()).isEqualTo(Status.UP);
        assertThat(initial.getDetails())
                .containsEntry("lastSuccessAt", "never")
                .containsEntry("lastErrorAt", "never")
                .containsEntry("lastErrorType", "none")
                .containsEntry("consecutiveFailures", 0);
        assertThat(registry.find("download.worker.heartbeat.age.seconds").gauge()).isNotNull();
        assertThat(registry.find("download.worker.heartbeat.consecutive.failures").gauge())
                .isNotNull();

        heartbeat.failure(new IllegalStateException("not persisted"));
        heartbeat.failure(null);
        assertThat(heartbeat.health().getStatus()).isEqualTo(Status.UP);
        heartbeat.failure(new IllegalArgumentException("invalid"));

        Health failed = heartbeat.health();
        assertThat(failed.getStatus()).isEqualTo(Status.DOWN);
        assertThat(failed.getDetails())
                .containsEntry("lastErrorType", "IllegalArgumentException")
                .containsEntry("consecutiveFailures", 3);

        clock.advance(Duration.ofSeconds(2));
        heartbeat.success();
        Health recovered = heartbeat.health();
        assertThat(recovered.getStatus()).isEqualTo(Status.UP);
        assertThat(recovered.getDetails())
                .containsEntry("lastSuccessAt", clock.instant().toString())
                .containsEntry("consecutiveFailures", 0);
    }

    /**
     * Supera en un segundo el límite de frescura y comprueba estado DOWN; un nuevo pulso recupera
     * UP.
     */
    @Test
    void degradesWhenStaleAndPulseRestoresFreshness() {
        MutableClock clock = new MutableClock(START);
        DownloadWorkerHeartbeat heartbeat = new DownloadWorkerHeartbeat(
                clock,
                new SimpleMeterRegistry(),
                Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(31));
        Health stale = heartbeat.health();
        assertThat(stale.getStatus()).isEqualTo(Status.DOWN);
        assertThat(stale.getDetails()).containsEntry("heartbeatAgeSeconds", 31.0);

        heartbeat.pulse();
        assertThat(heartbeat.health().getStatus()).isEqualTo(Status.UP);
    }

    /**
     * Mantiene un instante UTC modificable para probar caducidad y recuperación sin esperas reales.
     *
     * @since 0.1.0
     * @version 0.1.0
     * @category Pruebas de integración y mensajería
     */
    private static final class MutableClock extends Clock {
        private Instant current;

        /**
         * Fija el instante inicial que devolverá el reloj de la prueba.
         *
         * @param current instante inicial del reloj controlado.
         */
        private MutableClock(Instant current) {
            this.current = current;
        }

        /**
         * Suma la duración al instante que observarán las siguientes consultas del latido.
         *
         * @param duration avance temporal que se aplica sin esperar tiempo real.
         */
        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        /**
         * Declara la zona fija del reloj de prueba.
         *
         * @return UTC.
         */
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        /**
         * Conserva este reloj UTC para las llamadas de la prueba, sin aplicar la zona solicitada.
         *
         * @param zone zona solicitada, ignorada por este doble que siempre opera en UTC.
         * @return la misma instancia del doble.
         */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /**
         * Expone el instante inicial o el último avance aplicado por el escenario.
         *
         * @return instante controlado actual.
         */
        @Override
        public Instant instant() {
            return current;
        }
    }
}
