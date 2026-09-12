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

/**
 * Expone la salud del consumidor a partir de latidos, última operación y fallos consecutivos.
 * Una tarea periódica mantiene el latido incluso sin correos; el indicador degrada la
 * disponibilidad
 * ante estancamiento o la racha de fallos definida por el estado compartido.
 *
 * @see
 *     es.ubu.batchdownloader.notification.infrastructure.messaging.RabbitNotificationRequestedListener
 *
 * @see es.ubu.batchdownloader.contracts.operations.WorkerHeartbeatState
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component("workerHeartbeat")
public class NotificationWorkerHeartbeat implements HealthIndicator {
    private static final int FAILURE_THRESHOLD = 3;

    private final WorkerHeartbeatState state;
    private final Clock clock;
    private final Duration staleAfter;

    /**
     * Inicializa el estado de salud y registra métricas de antigüedad del latido y fallos
     * consecutivos.
     *
     * @param clock Reloj usado para comparar reservas y registrar instantes en milisegundos UTC.
     * @param meterRegistry Registro de métricas sin identificadores de evento ni contenido del
     *     correo.
     *
     * @param staleAfter Antigüedad máxima del latido antes de degradar la disponibilidad.
     */
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

    /**
     * Registra el instante y tipo del fallo y aumenta la racha de errores sin conservar su mensaje.
     *
     * @param failure Fallo del intento; solo se conserva su tipo para evitar registrar datos
     *     sensibles.
     */
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

    /**
     * Calcula la antigüedad de una señal sin devolver valores negativos ante ajustes del reloj.
     *
     * @param instant Instante UTC al que se refiere la señal de actividad.
     * @return segundos transcurridos, con precisión de milisegundos y mínimo cero.
     */
    private double ageSeconds(Instant instant) {
        return Math.max(0, Duration.between(instant, clock.instant()).toMillis()) / 1_000.0;
    }

    /**
     * Representa un instante de salud o la ausencia de una operación previa.
     *
     * @param instant Instante UTC registrado; null indica que la operación nunca ha ocurrido.
     * @return instante ISO-8601, o never para null.
     */
    private static String nullableInstant(Instant instant) {
        return instant == null ? "never" : instant.toString();
    }
}
