package es.ubu.batchdownloader.notification.operations;

import es.ubu.batchdownloader.contracts.operations.WorkerHeartbeatState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Expone señales de avance, éxito y error del consumidor de notificaciones. */
@Component("workerHeartbeat")
public class NotificationWorkerHeartbeat implements HealthIndicator {
    private static final int FAILURE_THRESHOLD = 3;

    private final WorkerHeartbeatState state;
    private final Clock clock;
    private final Duration staleAfter;

    /** Inicializa el heartbeat y sus métricas de antigüedad y fallos consecutivos. */
    public NotificationWorkerHeartbeat(
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${notification.heartbeat.stale-after:PT1M}") Duration staleAfter) {
        this.clock = clock;
        this.staleAfter = staleAfter;
        state = new WorkerHeartbeatState(clock);
        Gauge.builder(
                        "notification.worker.heartbeat.age.seconds",
                        this,
                        heartbeat -> heartbeat.ageSeconds(heartbeat.state.snapshot().heartbeatAt()))
                .register(meterRegistry);
        Gauge.builder(
                        "notification.worker.heartbeat.consecutive.failures",
                        this,
                        heartbeat -> heartbeat.state.snapshot().consecutiveFailures())
                .register(meterRegistry);
    }

    /** Mantiene una señal independiente de la llegada de mensajes. */
    @Scheduled(fixedRateString = "${notification.heartbeat.interval:PT10S}")
    public void pulse() {
        state.pulse();
    }

    /** Registra una notificación completada. */
    public void success() {
        state.success();
    }

    /** Registra un fallo sin conservar el texto de la excepción. */
    public void failure(Throwable failure) {
        state.failure(failure);
    }

    /** Degrada readiness únicamente ante estancamiento o una racha persistente. */
    @Override
    public Health health() {
        WorkerHeartbeatState.Snapshot snapshot = state.snapshot();
        Health.Builder health = state.degraded(staleAfter, FAILURE_THRESHOLD)
                ? Health.down()
                : Health.up();
        return health
                .withDetail("heartbeatAt", snapshot.heartbeatAt())
                .withDetail("heartbeatAgeSeconds", ageSeconds(snapshot.heartbeatAt()))
                .withDetail("lastSuccessAt", nullableInstant(snapshot.successAt()))
                .withDetail("lastErrorAt", nullableInstant(snapshot.errorAt()))
                .withDetail("lastErrorType", snapshot.errorType() == null ? "none" : snapshot.errorType())
                .withDetail("consecutiveFailures", snapshot.consecutiveFailures())
                .build();
    }

    private double ageSeconds(Instant instant) {
        return Math.max(0, Duration.between(instant, clock.instant()).toMillis()) / 1_000.0;
    }

    private static String nullableInstant(Instant instant) {
        return instant == null ? "never" : instant.toString();
    }
}
