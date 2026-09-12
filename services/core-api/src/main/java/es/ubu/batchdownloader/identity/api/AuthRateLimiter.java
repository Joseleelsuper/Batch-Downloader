package es.ubu.batchdownloader.identity.api;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import es.ubu.batchdownloader.common.RateLimitException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Mantiene cuotas locales independientes para acceso, registro, recuperación y reenvío, usando
 * contadores por IP e identidad normalizada.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.api.IdentityController
 * @see es.ubu.batchdownloader.identity.api.AdminIdentityController
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Component
class AuthRateLimiter {
    /** Contadores de login con ventana de un minuto. */
    private final Cache<String, AtomicInteger> logins;
    /** Contadores de registro con ventana de una hora. */
    private final Cache<String, AtomicInteger> registrations;
    /** Contadores de restablecimiento con ventana de una hora. */
    private final Cache<String, AtomicInteger> resets;
    private final Cache<String, AtomicInteger> verifications;
    /** Máximo de login por minuto. */
    private final int loginLimit;
    /** Máximo de registros por hora. */
    private final int registrationLimit;
    /** Máximo de restablecimientos por hora. */
    private final int resetLimit;
    private final int verificationLimit;

    /**
     * Crea cachés acotadas a veinte mil identidades con ventanas de un minuto para acceso y una
     * hora para el resto.
     *
     * @param loginLimit Intentos máximos de acceso por combinación de IP e identidad durante un
     *     minuto.
     * @param registrationLimit Registros máximos por combinación de IP y correo durante una hora.
     * @param resetLimit Solicitudes máximas de recuperación por combinación de IP y correo durante
     *     una hora.
     * @param verificationLimit Reenvíos máximos de verificación por combinación de IP y correo
     *     durante una hora.
     */
    @Autowired
    AuthRateLimiter(
            @Value("${app.auth.login-max-per-minute}") int loginLimit,
            @Value("${app.auth.register-max-per-hour}") int registrationLimit,
            @Value("${app.auth.reset-max-per-hour}") int resetLimit,
            @Value("${app.auth.verification-resend-max-per-hour}") int verificationLimit) {
        this.loginLimit = loginLimit;
        this.registrationLimit = registrationLimit;
        this.resetLimit = resetLimit;
        this.verificationLimit = verificationLimit;
        this.logins = cache(Duration.ofMinutes(1));
        this.registrations = cache(Duration.ofHours(1));
        this.resets = cache(Duration.ofHours(1));
        this.verifications = cache(Duration.ofHours(1));
    }

    /**
     * Crea cachés acotadas a veinte mil identidades con ventanas de un minuto para acceso y una
     * hora para el resto.
     *
     * @param loginLimit Intentos máximos de acceso por combinación de IP e identidad durante un
     *     minuto.
     * @param registrationLimit Registros máximos por combinación de IP y correo durante una hora.
     * @param resetLimit Solicitudes máximas de recuperación por combinación de IP y correo durante
     *     una hora.
     */
    AuthRateLimiter(int loginLimit, int registrationLimit, int resetLimit) {
        this(loginLimit, registrationLimit, resetLimit, registrationLimit);
    }

    /**
     * Consume un intento de acceso de la combinación IP y nombre o correo; el exceso produce un
     * error reintentable en 60 segundos.
     *
     * @param ip Dirección remota observada por Core; no se interpreta una cabecera aportada por el
     *     cliente.
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     */
    void login(String ip, String username) {
        require(logins, key(ip, username), loginLimit, 60);
    }

    /**
     * Consume cuota horaria de registro para la combinación IP y correo.
     *
     * @param ip Dirección remota observada por Core; no se interpreta una cabecera aportada por el
     *     cliente.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     */
    void registration(String ip, String email) {
        require(registrations, key(ip, email), registrationLimit, 3600);
    }

    /**
     * Consume cuota horaria de recuperación de contraseña sin compartirla con los accesos.
     *
     * @param ip Dirección remota observada por Core; no se interpreta una cabecera aportada por el
     *     cliente.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     */
    void reset(String ip, String email) {
        require(resets, key(ip, email), resetLimit, 3600);
    }

    /**
     * Consume cuota horaria de reenvío de verificación y comunica su agotamiento como error de
     * límite.
     *
     * @param ip Dirección remota observada por Core; no se interpreta una cabecera aportada por el
     *     cliente.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     */
    void verification(String ip, String email) {
        require(verifications, key(ip, email), verificationLimit, 3600);
    }

    /**
     * Consume la misma cuota de reenvío sin lanzar error, para conservar el fallo original de
     * correo no verificado durante el acceso.
     *
     * @param ip Dirección remota observada por Core; no se interpreta una cabecera aportada por el
     *     cliente.
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @return true si puede reenviarse; false cuando se supera la cuota.
     */
    boolean tryVerification(String ip, String email) {
        return verifications.get(key(ip, email), ignored -> new AtomicInteger()).incrementAndGet()
                <= verificationLimit;
    }

    /**
     * Crea una caché de hasta veinte mil contadores que caducan desde su primera escritura.
     *
     * @param window Duración del contador desde su creación; no se renueva en cada lectura.
     * @return caché local con expiración por ventana.
     */
    private static Cache<String, AtomicInteger> cache(Duration window) {
        return Caffeine.newBuilder()
                .maximumSize(20_000)
                .expireAfterWrite(window)
                .build();
    }

    /**
     * Incrementa atómicamente los intentos y rechaza el que supera el máximo permitido.
     *
     * @param counters Caché de contadores atómicos de la operación que consume cuota.
     * @param key Combinación normalizada de dirección remota e identidad, separadas por un carácter
     *     nulo.
     * @param limit Máximo de intentos admitidos dentro de la ventana del contador.
     * @param retryAfterSeconds Segundos comunicados al cliente cuando se agota la cuota.
     * @throws es.ubu.batchdownloader.common.RateLimitException si el nuevo contador excede el
     *     límite; incorpora el plazo de reintento de la operación.
     */
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

    /**
     * Normaliza dirección e identidad y las separa con un carácter nulo para evitar colisiones por
     * concatenación.
     *
     * @param ip Dirección remota observada por Core; no se interpreta una cabecera aportada por el
     *     cliente.
     * @param identity Correo o nombre del acceso cuyo contador se combina con la dirección remota.
     * @return clave de cuota; usa unknown para IP ausente y texto vacío para identidad nula.
     */
    private static String key(String ip, String identity) {
        String normalizedIp = ip == null || ip.isBlank() ? "unknown" : ip.strip();
        String normalizedIdentity = identity == null
                ? ""
                : identity.strip().toLowerCase(Locale.ROOT);
        return normalizedIp + '\u0000' + normalizedIdentity;
    }
}
