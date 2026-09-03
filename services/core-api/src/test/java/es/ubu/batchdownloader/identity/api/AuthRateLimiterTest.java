package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.common.RateLimitException;
import org.junit.jupiter.api.Test;

/** Verifica los límites locales de autenticación. */
class AuthRateLimiterTest {
    @Test
    void limitsLoginByIpAndNormalizedUsername() {
        AuthRateLimiter limiter = new AuthRateLimiter(10, 3, 3);
        for (int attempt = 0; attempt < 10; attempt++) {
            limiter.login("203.0.113.8", " User ");
        }

        assertThatThrownBy(() -> limiter.login("203.0.113.8", "user"))
                .isInstanceOf(RateLimitException.class)
                .satisfies(exception -> org.assertj.core.api.Assertions.assertThat(
                        ((RateLimitException) exception).retryAfterSeconds()).isEqualTo(60));
    }

    @Test
    void limitsRegistrationAndResetPerIpAndEmail() {
        AuthRateLimiter limiter = new AuthRateLimiter(10, 3, 3);
        for (int attempt = 0; attempt < 3; attempt++) {
            limiter.registration("203.0.113.9", "user@example.test");
            limiter.reset("203.0.113.9", "user@example.test");
        }

        assertThatThrownBy(() -> limiter.registration("203.0.113.9", "user@example.test"))
                .isInstanceOf(RateLimitException.class);
        assertThatThrownBy(() -> limiter.reset("203.0.113.9", "user@example.test"))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void reservesTheVerificationQuotaWithoutChangingTheLoginResponse() {
        AuthRateLimiter limiter = new AuthRateLimiter(10, 3, 3, 2);

        assertThat(limiter.tryVerification("203.0.113.10", "user@example.test")).isTrue();
        assertThat(limiter.tryVerification("203.0.113.10", "user@example.test")).isTrue();
        assertThat(limiter.tryVerification("203.0.113.10", "user@example.test")).isFalse();
    }
}
