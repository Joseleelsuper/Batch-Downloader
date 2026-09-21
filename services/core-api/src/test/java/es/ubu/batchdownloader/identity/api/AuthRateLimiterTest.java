package es.ubu.batchdownloader.identity.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.ubu.batchdownloader.common.RateLimitException;
import org.junit.jupiter.api.Test;

/** Verifica las cuotas de login administrativo y enlaces de acceso. */
class AuthRateLimiterTest {
    @Test
    void limitsAdminLoginByIpAndNormalizedUsername() {
        AuthRateLimiter limiter = new AuthRateLimiter(2, 3, 20);
        limiter.adminLogin("203.0.113.8", " Admin ");
        limiter.adminLogin("203.0.113.8", "admin");

        assertThatThrownBy(() -> limiter.adminLogin("203.0.113.8", "admin"))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void limitsMagicRequestsByEmailAndIp() {
        AuthRateLimiter limiter = new AuthRateLimiter(10, 2, 2);
        limiter.magicLinkRequest("203.0.113.9", "user@example.test");
        limiter.magicLinkRequest("203.0.113.10", "user@example.test");
        assertThatThrownBy(() -> limiter.magicLinkRequest("203.0.113.11", "user@example.test"))
                .isInstanceOf(RateLimitException.class);

        AuthRateLimiter byIp = new AuthRateLimiter(10, 10, 2);
        byIp.magicLinkRequest("203.0.113.9", "one@example.test");
        byIp.magicLinkRequest("203.0.113.9", "two@example.test");
        assertThatThrownBy(() -> byIp.magicLinkRequest("203.0.113.9", "three@example.test"))
                .isInstanceOf(RateLimitException.class);
    }

    @Test
    void limitsMagicConfirmationsSeparatelyFromRequests() {
        AuthRateLimiter limiter = new AuthRateLimiter(2, 3, 20);
        limiter.magicLinkConfirmation("203.0.113.10");
        limiter.magicLinkConfirmation("203.0.113.10");
        assertThatThrownBy(() -> limiter.magicLinkConfirmation("203.0.113.10"))
                .isInstanceOf(RateLimitException.class);
    }
}
