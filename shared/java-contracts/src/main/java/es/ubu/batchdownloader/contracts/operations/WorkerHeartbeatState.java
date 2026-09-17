package es.ubu.batchdownloader.contracts.operations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Conserva señales de vida, éxitos y fallos para evaluar la salud de workers sin depender de
 * Spring.
 *
 * Actualiza los campos mediante referencias y contadores atómicos. Un éxito borra la racha de
 * fallos, pero conserva la evidencia del último error; un simple latido no oculta fallos. La
 * instantánea reúne lecturas individuales y no garantiza una vista transaccional entre campos.
 *
 * @see java.time.Clock
 * @see WorkerHeartbeatState.Snapshot
 * @since 0.2.0-SNAPSHOT
 * @version 0.2.0-SNAPSHOT
 * @category Contratos compartidos
 */
public final class WorkerHeartbeatState {
    private final Clock clock;
    private final AtomicReference<Instant> heartbeatAt;
    private final AtomicReference<Instant> successAt = new AtomicReference<>();
    private final AtomicReference<Instant> errorAt = new AtomicReference<>();
    private final AtomicReference<String> errorType = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /**
     * Inicia el latido con el reloj recibido y deja sin registrar resultados previos.
     *
     * @param clock Reloj usado por todas las señales operativas de esta instancia.
     * @throws NullPointerException si no se proporciona un reloj.
     */
    public WorkerHeartbeatState(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        heartbeatAt = new AtomicReference<>(clock.instant());
    }

    /**
     * Renueva únicamente el instante del latido sin alterar éxitos, errores ni su racha.
     */
    public void pulse() {
        heartbeatAt.set(clock.instant());
    }

    /**
     * Registra el instante de éxito y de actividad y reinicia la racha de fallos consecutivos.
     */
    public void success() {
        Instant now = clock.instant();
        heartbeatAt.set(now);
        successAt.set(now);
        consecutiveFailures.set(0);
    }

    /**
     * Registra actividad, instante y tipo del fallo y aumenta la racha sin conservar contenido de
     * la excepción.
     *
     * @param failure Fallo del intento; solo se conserva su tipo, con UnknownFailure para null.
     */
    public void failure(Throwable failure) {
        Instant now = clock.instant();
        heartbeatAt.set(now);
        errorAt.set(now);
        errorType.set(failure == null ? "UnknownFailure" : failure.getClass().getSimpleName());
        consecutiveFailures.incrementAndGet();
    }

    /**
     * Reúne los valores operativos actuales para exponerlos sin ceder sus referencias mutables.
     *
     * @return copia de los campos leídos; las actualizaciones concurrentes pueden intercalarse
     *     entre lecturas.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                heartbeatAt.get(),
                successAt.get(),
                errorAt.get(),
                errorType.get(),
                consecutiveFailures.get());
    }

    /**
     * Comprueba si el latido excedió su vigencia o se alcanzó el número permitido de fallos
     * consecutivos.
     *
     * @param staleAfter Duración estrictamente positiva que puede transcurrir sin recibir un
     *     latido.
     *
     * @param failureThreshold Número positivo de fallos consecutivos a partir del que se degrada el
     *     estado.
     *
     * @return true ante latido vencido o racha igual o superior al umbral.
     * @throws IllegalArgumentException si la duración no es positiva o el umbral de fallos es menor
     *     que uno.
     */
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

    /**
     * Transporta una lectura de señales operativas sin exponer contadores o referencias mutables.
     *
     * @see es.ubu.batchdownloader.contracts.operations.WorkerHeartbeatState
     * @since 0.2.0-SNAPSHOT
     * @version 0.2.0-SNAPSHOT
     * @category Contratos compartidos
     */
    public record Snapshot(
            Instant heartbeatAt,
            Instant successAt,
            Instant errorAt,
            String errorType,
            int consecutiveFailures) {
    }
}
