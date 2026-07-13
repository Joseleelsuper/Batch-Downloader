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

@Service
public class IdentityService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private final UserAccountStore users;
    private final IdentityTokenStore tokens;
    private final PasswordHasher passwords;
    private final IdentityEventPublisher events;
    private final Clock clock;
    private final Duration verificationTtl;
    private final Duration resetTtl;

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

    @Transactional(readOnly = true)
    public IdentityView findByUsername(String username) {
        return IdentityView.from(requireByUsername(username));
    }

    @Transactional(readOnly = true)
    public UserAccount requireByUsername(String username) {
        return users.findByNormalizedUsername(normalize(username))
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new NotFoundException("user_not_found", "No existe el usuario."));
    }

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

    @Transactional
    public void requestPasswordReset(String email) {
        users.findByNormalizedEmail(normalize(email))
                .filter(UserAccount::enabled)
                .ifPresent(user -> issueToken(user, IdentityToken.Type.PASSWORD_RESET, resetTtl));
    }

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

    @Transactional
    public IdentityView updateNotificationPreference(String username, boolean enabled) {
        UserAccount user = requireByUsername(username);
        user.updateNotificationPreference(enabled, clock.instant());
        return IdentityView.from(users.save(user));
    }

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

    private IdentityToken requireUsableToken(String rawToken, IdentityToken.Type type) {
        return tokens.findByHashAndType(hashToken(rawToken), type)
                .filter(token -> token.usableAt(clock.instant()))
                .orElseThrow(() -> new BadRequestException("invalid_or_expired_token", "El token no es válido o ha caducado."));
    }

    static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
