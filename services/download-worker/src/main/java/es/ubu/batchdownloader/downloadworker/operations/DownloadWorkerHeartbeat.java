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

/**
 * Expone salud y métricas del worker usando latidos programados y resultados del consumidor, sin
 * guardar mensajes ni causas sensibles del fallo.
 *
 * @see es.ubu.batchdownloader.downloadworker.messaging.DownloadJobListener
 * @see WorkerHeartbeatState
 * @since 0.1.0
 * @version 0.1.0
 * @category Mensajería y operación del worker
 */
@Component("workerHeartbeat")
public class DownloadWorkerHeartbeat implements HealthIndicator {
    private static final int FAILURE_THRESHOLD = 3;

    private final WorkerHeartbeatState state;
    private final Clock clock;
    private final Duration staleAfter;

    /**
     * Inicializa el estado operativo y registra antigüedad del latido y fallos consecutivos.
     *
     * @param clock Reloj común que fecha resultados y señales de salud.
     * @param meterRegistry Registro de antigüedad del latido y fallos consecutivos.
     * @param staleAfter Tiempo máximo tolerado sin un latido reciente antes de degradar la salud.
     */
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

    /**
     * Actualiza el latido que acredita que la tarea programada del proceso sigue ejecutándose.
     */
    @Scheduled(fixedRateString = "${download-worker.heartbeat.interval:PT10S}")
    public void pulse() {
        state.pulse();
    }

    /**
     * Registra un mensaje atendido y reinicia la racha de fallos según el contrato de salud
     * compartido.
     */
    public void success() {
        state.success();
    }

    /**
     * Registra la clase del fallo y su instante en la señal de salud compartida.
     *
     * @param failure Fallo cuya clase queda registrada en la señal de salud.
     */
    public void failure(Throwable failure) {
        state.failure(failure);
    }

    /**
     * Consulta una instantánea operativa y declara DOWN por latido antiguo o tres fallos
     * consecutivos; expone fechas, antigüedad y tipo del último fallo.
     *
     * @return salud con detalles operativos sin mensajes de excepción.
     */
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
     * Calcula la antigüedad respecto al reloj común en segundos fraccionarios y evita valores
     * negativos.
     *
     * @param instant Instante que se convierte en antigüedad o representación textual.
     * @return segundos transcurridos, con cero para un instante futuro.
     */
    private double ageSeconds(Instant instant) {
        return Math.max(0, Duration.between(instant, clock.instant()).toMillis()) / 1_000.0;
    }

    /**
     * Representa una fecha opcional del diagnóstico de salud sin devolver un valor null.
     *
     * @param instant Instante que se convierte en antigüedad o representación textual.
     * @return fecha ISO textual o never si todavía no ocurrió.
     */
    private static String nullableInstant(Instant instant) {
        return instant == null ? "never" : instant.toString();
    }
}
