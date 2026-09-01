package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.GoneException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.identity.application.port.AccountSessionInvalidator;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Casos de uso de cuentas locales, perfil y tokens de identidad. */
@Service
public class IdentityService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] USERNAME_SUFFIX = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private final UserAccountStore users;
    private final IdentityTokenStore tokens;
    private final PasswordHasher passwords;
    private final IdentityEventPublisher events;
    private final AccountSessionInvalidator sessions;
    private final Clock clock;
    private final Duration verificationTtl;
    private final Duration resetTtl;
    private final TransactionTemplate transactions;

    public IdentityService(
            UserAccountStore users,
            IdentityTokenStore tokens,
            PasswordHasher passwords,
            IdentityEventPublisher events,
            AccountSessionInvalidator sessions,
            Clock clock,
            @Value("${app.auth.verification-ttl}") Duration verificationTtl,
            @Value("${app.auth.password-reset-ttl}") Duration resetTtl,
            TransactionTemplate transactions) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        this.events = events;
        this.sessions = sessions;
        this.clock = clock;
        this.verificationTtl = verificationTtl;
        this.resetTtl = resetTtl;
        this.transactions = transactions;
    }

    /** Registra una cuenta local y encola su verificación en la misma transacción. */
    public IdentityView register(String email, String rawPassword) {
        PasswordPolicy.requireValid(rawPassword);
        String cleanEmail = cleanEmail(email);
        String normalizedEmail = normalize(cleanEmail);
        if (users.existsByNormalizedEmail(normalizedEmail)) throw emailAlreadyExists();

        String passwordHash = passwords.hash(rawPassword);
        String baseUsername = UsernamePolicy.fromEmail(cleanEmail);
        for (int attempt = 0; attempt < 12; attempt++) {
            String username = attempt == 0
                    ? baseUsername
                    : UsernamePolicy.collisionCandidate(baseUsername, randomUsernameSuffix());
            try {
                IdentityView created = transactions.execute(status -> {
                    if (users.existsByNormalizedEmail(normalizedEmail)) throw emailAlreadyExists();
                    if (users.existsByNormalizedUsername(UsernamePolicy.normalize(username))) {
                        throw new UsernameCollisionException();
                    }
                    Instant now = clock.instant();
                    UserAccount user = users.save(UserAccount.register(
                            username,
                            UsernamePolicy.normalize(username),
                            cleanEmail,
                            normalizedEmail,
                            passwordHash,
                            now));
                    issueToken(user, IdentityToken.Type.EMAIL_VERIFICATION, verificationTtl);
                    return view(user);
                });
                if (created != null) return created;
            } catch (UsernameCollisionException exception) {
                // Se elige un nuevo sufijo sin repetir BCrypt.
            } catch (DataIntegrityViolationException exception) {
                if (users.existsByNormalizedEmail(normalizedEmail)) throw emailAlreadyExists();
            }
        }
        throw new ConflictException(
                "username_generation_failed", "No se pudo reservar un username para la cuenta.");
    }

    @Transactional(readOnly = true)
    public IdentityView findById(UUID id) {
        return view(requireById(id));
    }

    @Transactional(readOnly = true)
    public IdentityView findByUsername(String username) {
        return view(requireByUsername(username));
    }

    @Transactional(readOnly = true)
    public UserAccount requireById(UUID id) {
        return users.findById(id).filter(UserAccount::enabled)
                .orElseThrow(() -> new NotFoundException("user_not_found", "No existe el usuario."));
    }

    @Transactional(readOnly = true)
    public UserAccount requireByUsername(String username) {
        return users.findByNormalizedUsername(normalize(username)).filter(UserAccount::enabled)
                .orElseThrow(() -> new NotFoundException("user_not_found", "No existe el usuario."));
    }

    @Transactional
    public void confirmEmail(String rawToken) {
        IdentityToken token = requireUsableToken(rawToken, IdentityToken.Type.EMAIL_VERIFICATION, true);
        UserAccount user = users.findById(token.userId())
                .orElseThrow(() -> invalidToken(IdentityToken.Type.EMAIL_VERIFICATION));
        Instant now = clock.instant();
        user.verifyEmail(now);
        token.consume(now);
        users.save(user);
        tokens.save(token);
    }

    /** Respuesta deliberadamente uniforme para no revelar cuentas. */
    @Transactional
    public void resendEmailVerification(String email) {
        users.findByNormalizedEmail(normalize(email))
                .filter(UserAccount::enabled)
                .filter(user -> !user.emailVerified())
                .ifPresent(user -> issueToken(user, IdentityToken.Type.EMAIL_VERIFICATION, verificationTtl));
    }

    /** Respuesta deliberadamente uniforme para cuentas inexistentes. */
    @Transactional
    public void requestPasswordReset(String email) {
        users.findByNormalizedEmail(normalize(email))
                .filter(UserAccount::enabled)
                .ifPresent(user -> issueToken(user, IdentityToken.Type.PASSWORD_RESET, resetTtl));
    }

    public void resetPassword(String rawToken, String newPassword) {
        PasswordPolicy.requireValid(newPassword);
        transactions.executeWithoutResult(status ->
                requireUsableToken(rawToken, IdentityToken.Type.PASSWORD_RESET, false));
        String passwordHash = passwords.hash(newPassword);
        UserAccount changed = transactions.execute(status -> {
            IdentityToken token = requireUsableToken(rawToken, IdentityToken.Type.PASSWORD_RESET, true);
            UserAccount user = users.findById(token.userId())
                    .orElseThrow(() -> invalidToken(IdentityToken.Type.PASSWORD_RESET));
            Instant now = clock.instant();
            user.changePassword(passwordHash, now);
            token.consume(now);
            tokens.save(token);
            return users.save(user);
        });
        if (changed != null) sessions.invalidateAll(changed.id());
    }

    @Transactional
    public IdentityView updateUsername(UUID userId, String requestedUsername) {
        UserAccount user = requireById(userId);
        String clean = UsernamePolicy.validateManual(requestedUsername);
        String normalized = UsernamePolicy.normalize(clean);
        if (!normalized.equals(user.normalizedUsername()) && users.existsByNormalizedUsername(normalized)) {
            throw usernameTaken();
        }
        try {
            user.changeUsername(clean, normalized, clock.instant());
            return view(users.save(user));
        } catch (DataIntegrityViolationException exception) {
            throw usernameTaken();
        }
    }

    @Transactional
    public IdentityView updateNotificationPreference(UUID userId, boolean enabled) {
        UserAccount user = requireById(userId);
        user.updateNotificationPreference(enabled, clock.instant());
        return view(users.save(user));
    }

    @Transactional
    public UserAccount markVerified(UUID userId) {
        UserAccount user = requireById(userId);
        if (!user.emailVerified()) {
            user.verifyEmail(clock.instant());
            return users.save(user);
        }
        return user;
    }

    public IdentityView view(UserAccount user) {
        return IdentityView.from(user);
    }

    private void issueToken(UserAccount user, IdentityToken.Type type, Duration ttl) {
        Instant now = clock.instant();
        tokens.invalidateUnconsumedForUser(user.id(), type, now);
        String rawToken = newRawToken();
        tokens.save(IdentityToken.issue(user.id(), hashToken(rawToken), type, now.plus(ttl), now));
        if (type == IdentityToken.Type.EMAIL_VERIFICATION) {
            events.emailVerificationRequested(user, rawToken);
        } else {
            events.passwordResetRequested(user, rawToken);
        }
    }

    private IdentityToken requireUsableToken(
            String rawToken, IdentityToken.Type type, boolean forUpdate) {
        String hash = hashToken(rawToken);
        IdentityToken token = (forUpdate
                        ? tokens.findByHashAndTypeForUpdate(hash, type)
                        : tokens.findByHashAndType(hash, type))
                .orElseThrow(() -> invalidToken(type));
        if (token.consumedAt() != null) throw usedToken(type);
        if (!token.expiresAt().isAfter(clock.instant())) throw expiredToken(type);
        return token;
    }

    private BadRequestException invalidToken(IdentityToken.Type type) {
        return new BadRequestException(tokenPrefix(type) + "_token_invalid", "El token no es válido.");
    }

    private GoneException expiredToken(IdentityToken.Type type) {
        return new GoneException(tokenPrefix(type) + "_token_expired", "El token ha caducado.");
    }

    private GoneException usedToken(IdentityToken.Type type) {
        return new GoneException(tokenPrefix(type) + "_token_used", "El token ya se ha utilizado.");
    }

    private String tokenPrefix(IdentityToken.Type type) {
        return type == IdentityToken.Type.EMAIL_VERIFICATION ? "verification" : "reset";
    }

    private ConflictException emailAlreadyExists() {
        return new ConflictException("email_already_exists", "El correo ya está registrado.");
    }

    private ConflictException usernameTaken() {
        return new ConflictException("username_taken", "El username ya está ocupado.");
    }

    private static String cleanEmail(String value) {
        return value == null ? "" : value.strip();
    }

    public static String normalize(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    public static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomUsernameSuffix() {
        char[] suffix = new char[8];
        for (int index = 0; index < suffix.length; index++) {
            suffix[index] = USERNAME_SUFFIX[SECURE_RANDOM.nextInt(USERNAME_SUFFIX.length)];
        }
        return new String(suffix);
    }

    private static final class UsernameCollisionException extends RuntimeException {}
}
