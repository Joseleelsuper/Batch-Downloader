package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.application.port.PasswordHasher;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordina las operaciones de negocio de {@code IdentityService}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Service
public class IdentityService {
    /**
     * Constante que define {@code SECURE_RANDOM}.
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /**
     * Estado {@code users} mantenido por {@code IdentityService}.
     */
    private final UserAccountStore users;
    /**
     * Estado {@code tokens} mantenido por {@code IdentityService}.
     */
    private final IdentityTokenStore tokens;
    /**
     * Estado {@code passwords} mantenido por {@code IdentityService}.
     */
    private final PasswordHasher passwords;
    /**
     * Estado {@code events} mantenido por {@code IdentityService}.
     */
    private final IdentityEventPublisher events;
    /**
     * Estado {@code clock} mantenido por {@code IdentityService}.
     */
    private final Clock clock;
    /**
     * Estado {@code verificationTtl} mantenido por {@code IdentityService}.
     */
    private final Duration verificationTtl;
    /**
     * Estado {@code resetTtl} mantenido por {@code IdentityService}.
     */
    private final Duration resetTtl;

    /**
     * Inicializa una instancia de {@code IdentityService}.
     *
     * @param users Valor de {@code users} utilizado por la operación.
     * @param tokens Valor de {@code tokens} utilizado por la operación.
     * @param passwords Valor de {@code passwords} utilizado por la operación.
     * @param events Valor de {@code events} utilizado por la operación.
     * @param clock Valor de {@code clock} utilizado por la operación.
     * @param verificationTtl Valor de {@code verificationTtl} utilizado por la operación.
     * @param resetTtl Valor de {@code resetTtl} utilizado por la operación.
     */
    public IdentityService(
            UserAccountStore users,
            IdentityTokenStore tokens,
            PasswordHasher passwords,
            IdentityEventPublisher events,
            Clock clock,
            @Value("${app.auth.verification-ttl}") Duration verificationTtl,
            @Value("${app.auth.password-reset-ttl}") Duration resetTtl) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.events = events;
        this.clock = clock;
        this.verificationTtl = verificationTtl;
        this.resetTtl = resetTtl;
    }

    /**
     * Ejecuta la operación {@code register}.
     *
     * @param username Valor de {@code username} utilizado por la operación.
     * @param email Dirección de correo electrónico asociada a la operación.
     * @param rawPassword Valor de {@code rawPassword} utilizado por la operación.
     * @return Resultado producido por {@code register}.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @Transactional
    public IdentityView register(String username, String email, String rawPassword) {
        String cleanUsername = username.strip();
        String cleanEmail = email.strip();
        String normalizedUsername = normalize(cleanUsername);
        String normalizedEmail = normalize(cleanEmail);
        if (users.existsByNormalizedUsername(normalizedUsername)) {
            throw new ConflictException("username_already_exists", "El nombre de usuario ya está registrado.");
        }
        if (users.existsByNormalizedEmail(normalizedEmail)) {
            throw new ConflictException("email_already_exists", "El correo ya está registrado.");
        }

        Instant now = clock.instant();
        UserAccount user = users.save(UserAccount.register(
                cleanUsername, normalizedUsername, cleanEmail, normalizedEmail, passwords.hash(rawPassword), now));
        issueToken(user, IdentityToken.Type.EMAIL_VERIFICATION, verificationTtl);
        return IdentityView.from(user);
    }

    /**
     * Busca el resultado solicitado mediante {@code findByUsername}.
     *
     * @param username Valor de {@code username} utilizado por la operación.
     * @return Resultado producido por {@code findByUsername}.
     */
    @Transactional(readOnly = true)
    public IdentityView findByUsername(String username) {
        return IdentityView.from(requireByUsername(username));
    }

    /**
     * Ejecuta la operación {@code requireByUsername}.
     *
     * @param username Valor de {@code username} utilizado por la operación.
     * @return Resultado producido por {@code requireByUsername}.
     */
    @Transactional(readOnly = true)
    public UserAccount requireByUsername(String username) {
        return users.findByNormalizedUsername(normalize(username))
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new NotFoundException("user_not_found", "No existe el usuario."));
    }

    /**
     * Ejecuta la operación {@code confirmEmail}.
     *
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     */
    @Transactional
    public void confirmEmail(String rawToken) {
        IdentityToken token = requireUsableToken(rawToken, IdentityToken.Type.EMAIL_VERIFICATION);
        UserAccount user = users.findById(token.userId())
                .orElseThrow(() -> new BadRequestException("invalid_token", "El token no es válido."));
        Instant now = clock.instant();
        user.verifyEmail(now);
        token.consume(now);
        users.save(user);
        tokens.save(token);
    }

    /**
     * Ejecuta la operación {@code requestPasswordReset}.
     *
     * @param email Dirección de correo electrónico asociada a la operación.
     */
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByNormalizedEmail(normalize(email))
                .filter(UserAccount::enabled)
                .ifPresent(user -> issueToken(user, IdentityToken.Type.PASSWORD_RESET, resetTtl));
    }

    /**
     * Ejecuta la operación {@code resetPassword}.
     *
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     * @param newPassword Valor de {@code newPassword} utilizado por la operación.
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        IdentityToken token = requireUsableToken(rawToken, IdentityToken.Type.PASSWORD_RESET);
        UserAccount user = users.findById(token.userId())
                .orElseThrow(() -> new BadRequestException("invalid_token", "El token no es válido."));
        Instant now = clock.instant();
        user.changePassword(passwords.hash(newPassword), now);
        token.consume(now);
        users.save(user);
        tokens.save(token);
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updateNotificationPreference}.
     *
     * @param username Valor de {@code username} utilizado por la operación.
     * @param enabled Valor de {@code enabled} utilizado por la operación.
     * @return Resultado producido por {@code updateNotificationPreference}.
     */
    @Transactional
    public IdentityView updateNotificationPreference(String username, boolean enabled) {
        UserAccount user = requireByUsername(username);
        user.updateNotificationPreference(enabled, clock.instant());
        return IdentityView.from(users.save(user));
    }

    /**
     * Indica si se cumple la condición mediante {@code issueToken}.
     *
     * @param user Valor de {@code user} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @param ttl Valor de {@code ttl} utilizado por la operación.
     */
    private void issueToken(UserAccount user, IdentityToken.Type type, Duration ttl) {
        tokens.invalidateUnconsumedForUser(user.id(), type);
        String rawToken = newRawToken();
        Instant now = clock.instant();
        tokens.save(IdentityToken.issue(user.id(), hashToken(rawToken), type, now.plus(ttl), now));
        if (type == IdentityToken.Type.EMAIL_VERIFICATION) {
            events.emailVerificationRequested(user, rawToken);
        } else {
            events.passwordResetRequested(user, rawToken);
        }
    }

    /**
     * Ejecuta la operación {@code requireUsableToken}.
     *
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Resultado producido por {@code requireUsableToken}.
     */
    private IdentityToken requireUsableToken(String rawToken, IdentityToken.Type type) {
        return tokens.findByHashAndType(hashToken(rawToken), type)
                .filter(token -> token.usableAt(clock.instant()))
                .orElseThrow(() -> new BadRequestException("invalid_or_expired_token", "El token no es válido o ha caducado."));
    }

    /**
     * Normaliza el valor recibido mediante {@code normalize}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code normalize}.
     */
    static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Indica si existe el recurso mediante {@code hashToken}.
     *
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     * @return Resultado producido por {@code hashToken}.
     * @throws IllegalStateException Si el estado actual impide completar la operación.
     */
    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /**
     * Ejecuta la operación {@code newRawToken}.
     *
     * @return Resultado producido por {@code newRawToken}.
     */
    private static String newRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
