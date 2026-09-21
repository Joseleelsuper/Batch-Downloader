package es.ubu.batchdownloader.identity.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import es.ubu.batchdownloader.common.RateLimitException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Mantiene cuotas locales para login administrativo y enlaces de acceso. */
@Component
class AuthRateLimiter {
    private final Cache<String, AtomicInteger> adminLogins;
    private final Cache<String, AtomicInteger> magicRequestsByEmail;
    private final Cache<String, AtomicInteger> magicRequestsByIp;
    private final Cache<String, AtomicInteger> magicConfirmations;
    private final int loginLimit;
    private final int magicEmailLimit;
    private final int magicIpLimit;

    @Autowired
    AuthRateLimiter(
            @Value("${app.auth.login-max-per-minute}") int loginLimit,
            @Value("${app.auth.magic-link-max-per-email-hour}") int magicEmailLimit,
            @Value("${app.auth.magic-link-max-per-ip-hour}") int magicIpLimit) {
        this.loginLimit = loginLimit;
        this.magicEmailLimit = magicEmailLimit;
        this.magicIpLimit = magicIpLimit;
        this.adminLogins = cache(Duration.ofMinutes(1));
        this.magicConfirmations = cache(Duration.ofMinutes(1));
        this.magicRequestsByEmail = cache(Duration.ofHours(1));
        this.magicRequestsByIp = cache(Duration.ofHours(1));
    }

    void adminLogin(String ip, String username) {
        require(adminLogins, key(ip, username), loginLimit, 60);
    }

    void magicLinkRequest(String ip, String email) {
        require(magicRequestsByIp, normalizeIp(ip), magicIpLimit, 3600);
        require(magicRequestsByEmail, normalizeIdentity(email), magicEmailLimit, 3600);
    }

    void magicLinkConfirmation(String ip) {
        require(magicConfirmations, normalizeIp(ip), loginLimit, 60);
    }

    private static Cache<String, AtomicInteger> cache(Duration window) {
        return Caffeine.newBuilder().maximumSize(20_000).expireAfterWrite(window).build();
    }

    private static void require(
            Cache<String, AtomicInteger> counters, String key, int limit, int retryAfterSeconds) {
        int attempts = counters.get(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (attempts > limit) {
            throw new RateLimitException(
                    "rate_limited",
                    "Se han realizado demasiadas solicitudes. Inténtalo más tarde.",
                    retryAfterSeconds);
        }
    }

    private static String key(String ip, String identity) {
        return normalizeIp(ip) + '\u0000' + normalizeIdentity(identity);
    }

    private static String normalizeIp(String ip) {
        return ip == null || ip.isBlank() ? "unknown" : ip.strip();
    }

    private static String normalizeIdentity(String identity) {
        return identity == null ? "" : identity.strip().toLowerCase(Locale.ROOT);
    }
}
