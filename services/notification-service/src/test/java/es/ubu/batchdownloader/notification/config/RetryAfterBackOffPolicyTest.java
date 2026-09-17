package es.ubu.batchdownloader.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryContext;
import org.springframework.retry.context.RetryContextSupport;

/**
 * Comprueba las esperas de reintento con un sleeper controlado y sin pausas reales.
 *
 * @see es.ubu.batchdownloader.notification.config.RetryAfterBackOffPolicy
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class RetryAfterBackOffPolicyTest {
    /**
     * Comprueba que una causa temporal con Retry-After determina la espera del siguiente intento.
     */
    @Test
    void usesProviderRetryAfterWhenPresent() {
        List<Long> sleeps = new ArrayList<>();
        RetryAfterBackOffPolicy policy = new RetryAfterBackOffPolicy(
                Duration.ofSeconds(1), 2, Duration.ofSeconds(10), sleeps::add);
        RetryContextSupport context = new RetryContextSupport(null);
        context.registerThrowable(new IllegalStateException("wrapper",
                new RetryableNotificationException(
                        "resend_temporarily_unavailable", Duration.ofSeconds(17))));

        policy.backOff(policy.start(context));

        assertThat(sleeps).containsExactly(17_000L);
    }

    /**
     * Comprueba que los intentos sin demora del proveedor siguen los intervalos exponenciales
     * configurados.
     */
    @Test
    void fallsBackToConfiguredExponentialIntervals() {
        List<Long> sleeps = new ArrayList<>();
        RetryAfterBackOffPolicy policy = new RetryAfterBackOffPolicy(
                Duration.ofSeconds(1), 2, Duration.ofSeconds(10), sleeps::add);
        RetryContext context = new RetryContextSupport(null);
        var backOff = policy.start(context);

        policy.backOff(backOff);
        policy.backOff(backOff);
        policy.backOff(backOff);

        assertThat(sleeps).containsExactly(1_000L, 2_000L, 4_000L);
    }
}
