package es.ubu.batchdownloader.contracts.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Estado concurrente y agnóstico del framework para supervisar un consumidor persistente.
 *
 * <p>Un único fallo no degrada la capacidad: sólo una racha configurable de errores o un
 * heartbeat estancado cambia el estado expuesto por el adaptador de salud.
 */
public final class WorkerHeartbeatState {
    private final Clock clock;
    private final AtomicReference<Instant> heartbeatAt;
    private final AtomicReference<Instant> successAt = new AtomicReference<>();
    private final AtomicReference<Instant> errorAt = new AtomicReference<>();
    private final AtomicReference<String> errorType = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /** Inicializa el estado como vivo, sin inventar un trabajo procesado con éxito. */
    public WorkerHeartbeatState(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        heartbeatAt = new AtomicReference<>(clock.instant());
    }

    /** Señala que el bucle de supervisión continúa avanzando. */
    public void pulse() {
        heartbeatAt.set(clock.instant());
    }

    /** Registra la finalización correcta de una unidad de trabajo. */
    public void success() {
        Instant now = clock.instant();
        heartbeatAt.set(now);
        successAt.set(now);
        consecutiveFailures.set(0);
    }

    /** Registra un error mediante su tipo, sin conservar mensajes potencialmente sensibles. */
    public void failure(Throwable failure) {
        Instant now = clock.instant();
        heartbeatAt.set(now);
        errorAt.set(now);
        errorType.set(failure == null ? "UnknownFailure" : failure.getClass().getSimpleName());
        consecutiveFailures.incrementAndGet();
    }

    /** Obtiene una instantánea coherente para salud y métricas. */
    public Snapshot snapshot() {
        return new Snapshot(
                heartbeatAt.get(),
                successAt.get(),
                errorAt.get(),
                errorType.get(),
                consecutiveFailures.get());
    }

    /** Determina si la capacidad debe degradarse por estancamiento o fallos persistentes. */
    public boolean degraded(Duration staleAfter, int failureThreshold) {
        if (staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("worker_heartbeat_stale_after_must_be_positive");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("worker_heartbeat_failure_threshold_must_be_positive");
        }
        Snapshot current = snapshot();
        boolean stale = current.heartbeatAt().plus(staleAfter).isBefore(clock.instant());
        return stale || current.consecutiveFailures() >= failureThreshold;
    }

    /** Instantánea inmutable de señales operativas. */
    public record Snapshot(
            Instant heartbeatAt,
            Instant successAt,
            Instant errorAt,
            String errorType,
            int consecutiveFailures) {
    }
}
