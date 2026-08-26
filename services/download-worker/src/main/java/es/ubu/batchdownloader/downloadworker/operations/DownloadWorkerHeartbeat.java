package es.ubu.batchdownloader.downloadworker.operations;

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

/** Expone señales de avance, éxito y error del consumidor de descargas. */
@Component("workerHeartbeat")
public class DownloadWorkerHeartbeat implements HealthIndicator {
    private static final int FAILURE_THRESHOLD = 3;

    private final WorkerHeartbeatState state;
    private final Clock clock;
    private final Duration staleAfter;

    /** Inicializa el heartbeat y registra la antigüedad como métrica. */
    public DownloadWorkerHeartbeat(
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${download-worker.heartbeat.stale-after:PT1M}") Duration staleAfter) {
        this.clock = clock;
        this.staleAfter = staleAfter;
        state = new WorkerHeartbeatState(clock);
        Gauge.builder(
                        "download.worker.heartbeat.age.seconds",
                        this,
                        heartbeat -> heartbeat.ageSeconds(heartbeat.state.snapshot().heartbeatAt()))
                .register(meterRegistry);
        Gauge.builder(
                        "download.worker.heartbeat.consecutive.failures",
                        this,
                        heartbeat -> heartbeat.state.snapshot().consecutiveFailures())
                .register(meterRegistry);
    }

    /** Mantiene una señal independiente de la llegada de mensajes. */
    @Scheduled(fixedRateString = "${download-worker.heartbeat.interval:PT10S}")
    public void pulse() {
        state.pulse();
    }

    /** Registra una unidad de trabajo completada. */
    public void success() {
        state.success();
    }

    /** Registra un fallo sin almacenar el mensaje de la excepción. */
    public void failure(Throwable failure) {
        state.failure(failure);
    }

    /** Degrada readiness sólo por estancamiento o tres fallos consecutivos. */
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
