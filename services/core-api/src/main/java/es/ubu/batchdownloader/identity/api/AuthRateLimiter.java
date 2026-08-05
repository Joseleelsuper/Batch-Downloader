package es.ubu.batchdownloader.identity.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import es.ubu.batchdownloader.common.RateLimitException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Aplica límites locales por identidad e IP en la única instancia de Core.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
class AuthRateLimiter {
    /** Contadores de login con ventana de un minuto. */
    private final Cache<String, AtomicInteger> logins;
    /** Contadores de registro con ventana de una hora. */
    private final Cache<String, AtomicInteger> registrations;
    /** Contadores de restablecimiento con ventana de una hora. */
    private final Cache<String, AtomicInteger> resets;
    /** Máximo de login por minuto. */
    private final int loginLimit;
    /** Máximo de registros por hora. */
    private final int registrationLimit;
    /** Máximo de restablecimientos por hora. */
    private final int resetLimit;

    /**
     * Inicializa los contadores acotados.
     *
     * @param loginLimit Máximo de login por minuto.
     * @param registrationLimit Máximo de registros por hora.
     * @param resetLimit Máximo de restablecimientos por hora.
     */
    AuthRateLimiter(
            @Value("${app.auth.login-max-per-minute}") int loginLimit,
            @Value("${app.auth.register-max-per-hour}") int registrationLimit,
            @Value("${app.auth.reset-max-per-hour}") int resetLimit) {
        this.loginLimit = loginLimit;
        this.registrationLimit = registrationLimit;
        this.resetLimit = resetLimit;
        this.logins = cache(Duration.ofMinutes(1));
        this.registrations = cache(Duration.ofHours(1));
        this.resets = cache(Duration.ofHours(1));
    }

    /** Limita un intento de login. */
    void login(String ip, String username) {
        require(logins, key(ip, username), loginLimit, 60);
    }

    /** Limita un intento de registro. */
    void registration(String ip, String email) {
        require(registrations, key(ip, email), registrationLimit, 3600);
    }

    /** Limita una petición de restablecimiento. */
    void reset(String ip, String email) {
        require(resets, key(ip, email), resetLimit, 3600);
    }

    /** Crea una caché con tamaño defensivo y caducidad fija. */
    private static Cache<String, AtomicInteger> cache(Duration window) {
        return Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(window)
                .build();
    }

    /** Incrementa y valida un contador. */
    private static void require(
            Cache<String, AtomicInteger> counters,
            String key,
            int limit,
            int retryAfterSeconds) {
        int attempts = counters.get(key, ignored -> new AtomicInteger()).incrementAndGet();
        if (attempts > limit) {
            throw new RateLimitException(
                    "rate_limited",
                    "Se han realizado demasiadas solicitudes. Inténtalo más tarde.",
                    retryAfterSeconds);
        }
    }

    /** Normaliza la identidad sin almacenar credenciales en claro. */
    private static String key(String ip, String identity) {
        String normalizedIp = ip == null || ip.isBlank() ? "unknown" : ip.strip();
        String normalizedIdentity = identity == null
                ? ""
                : identity.strip().toLowerCase(Locale.ROOT);
        return normalizedIp + '\u0000' + normalizedIdentity;
    }
}
