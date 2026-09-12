package es.ubu.batchdownloader.notification.config;

import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import java.time.Duration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;

/**
 * Calcula esperas de reintento usando Retry-After o el crecimiento exponencial configurado.
 *
 * La demora explícita se acota entre un milisegundo y cinco minutos. La interrupción conserva
 * la señal del hilo y aborta la espera para permitir detener al consumidor.
 *
 * @see es.ubu.batchdownloader.notification.application.RetryableNotificationException
 * @see es.ubu.batchdownloader.notification.config.NotificationRetryConfiguration
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
final class RetryAfterBackOffPolicy implements BackOffPolicy {
    private final ExponentialBackOffPolicy fallback;
    private final Sleeper sleeper;

    /**
     * Prepara una política exponencial reutilizable con un estado independiente para cada
     * secuencia.
     *
     * @param initialInterval Espera inicial de la política exponencial.
     * @param multiplier Factor aplicado al intervalo entre intentos consecutivos.
     * @param maxInterval Límite de la espera exponencial entre intentos.
     * @param sleeper Operación de espera interrumpible; permite controlar el tiempo en pruebas.
     */
    RetryAfterBackOffPolicy(
            Duration initialInterval,
            double multiplier,
            Duration maxInterval,
            Sleeper sleeper) {
        this.sleeper = sleeper;
        this.fallback = new ExponentialBackOffPolicy();
        fallback.setInitialInterval(initialInterval.toMillis());
        fallback.setMultiplier(multiplier);
        fallback.setMaxInterval(maxInterval.toMillis());
        fallback.setSleeper(sleeper);
    }

    /**
     * Asocia el estado de la entrega con una nueva secuencia de espera exponencial.
     *
     * @param context Estado del intento y último fallo, mantenido por Spring Retry.
     * @return contexto que debe suministrarse a backOff en los siguientes intentos.
     */
    @Override
    public BackOffContext start(RetryContext context) {
        return new Context(context, fallback.start(context));
    }

    /**
     * Espera según la primera causa reintentable con demora explícita; sin ella usa el intervalo
     * exponencial.
     *
     * @param backOffContext Contexto devuelto por start para esta secuencia de reintentos.
     * @throws org.springframework.retry.backoff.BackOffInterruptedException si el hilo se
     *     interrumpe durante la espera.
     */
    @Override
    public void backOff(BackOffContext backOffContext) throws BackOffInterruptedException {
        Context context = (Context) backOffContext;
        RetryableNotificationException retryable = findRetryable(context.retry().getLastThrowable());
        if (retryable == null || retryable.retryAfter() == null) {
            fallback.backOff(context.fallback());
            return;
        }
        long delay = Math.max(1, Math.min(retryable.retryAfter().toMillis(), Duration.ofMinutes(5).toMillis()));
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BackOffInterruptedException("notification_retry_interrupted", exception);
        }
    }

    /**
     * Recorre las causas hasta encontrar la clasificación temporal del proveedor.
     *
     * @param exception Fallo que se describe o cuya cadena de causas se examina.
     * @return primera excepción reintentable, o null si ninguna causa tiene esa clasificación.
     */
    private static RetryableNotificationException findRetryable(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RetryableNotificationException retryable) return retryable;
            current = current.getCause();
        }
        return null;
    }

    /**
     * Conserva el último fallo de una entrega junto con su progresión independiente de espera
     * exponencial.
     *
     * @see es.ubu.batchdownloader.notification.config.RetryAfterBackOffPolicy
     * @since 0.1.0
     * @version 0.1.0
     * @category Notificaciones
     */
    private record Context(RetryContext retry, BackOffContext fallback) implements BackOffContext {}
}
