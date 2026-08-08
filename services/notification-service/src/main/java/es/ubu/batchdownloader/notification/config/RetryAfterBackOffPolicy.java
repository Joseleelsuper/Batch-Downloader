package es.ubu.batchdownloader.notification.config;

import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import java.time.Duration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.backoff.Sleeper;

/** Respeta Retry-After de Resend y conserva el backoff exponencial para el resto. */
final class RetryAfterBackOffPolicy implements BackOffPolicy {
    private final ExponentialBackOffPolicy fallback;
    private final Sleeper sleeper;

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

    @Override
    public BackOffContext start(RetryContext context) {
        return new Context(context, fallback.start(context));
    }

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

    private static RetryableNotificationException findRetryable(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof RetryableNotificationException retryable) return retryable;
            current = current.getCause();
        }
        return null;
    }

    private record Context(RetryContext retry, BackOffContext fallback) implements BackOffContext {}
}
