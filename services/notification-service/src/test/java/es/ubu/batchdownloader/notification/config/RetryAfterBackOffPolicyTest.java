package es.ubu.batchdownloader.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryContext;
import org.springframework.retry.context.RetryContextSupport;

class RetryAfterBackOffPolicyTest {
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
