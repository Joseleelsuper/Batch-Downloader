package es.ubu.batchdownloader.identity.application;

import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.GoneException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
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

/** Gestiona cuentas, perfiles y enlaces de acceso de un solo uso. */
@Service
public class IdentityService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final char[] USERNAME_SUFFIX = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();

    private final UserAccountStore users;
    private final IdentityTokenStore tokens;
    private final IdentityEventPublisher events;
    private final Clock clock;
    private final Duration magicLinkTtl;

    public IdentityService(
            UserAccountStore users,
            IdentityTokenStore tokens,
            IdentityEventPublisher events,
            Clock clock,
            @Value("${app.auth.magic-link-ttl}") Duration magicLinkTtl) {
        this.users = users;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
        this.magicLinkTtl = magicLinkTtl;
    }

    /** Solicita un enlace sin revelar si el correo ya tenía cuenta. */
    @Transactional
    public void requestMagicLink(String email) {
        String cleanEmail = cleanEmail(email);
        String normalizedEmail = normalize(cleanEmail);
        UserAccount user = users.findByNormalizedEmail(normalizedEmail).orElse(null);
        if (user == null) {
            user = createUser(cleanEmail, normalizedEmail);
        }
        if (user.enabled() && user.role() == UserRole.USER) {
            issueMagicLink(user);
        }
    }

    /** Consume el enlace, confirma el correo y devuelve la cuenta autenticada. */
    @Transactional
    public UserAccount consumeMagicLink(String rawToken) {
        IdentityToken token = requireUsableToken(rawToken, true);
        UserAccount user = users.findById(token.userId())
                .filter(account -> account.enabled() && account.role() == UserRole.USER)
                .orElseThrow(this::invalidToken);
        Instant now = clock.instant();
        token.consume(now);
        if (!user.emailVerified()) {
            user.verifyEmail(now);
        }
        users.save(user);
        tokens.save(token);
        return user;
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

    public IdentityView view(UserAccount user) {
        return IdentityView.from(user);
    }

    private UserAccount createUser(String email, String normalizedEmail) {
        String baseUsername = UsernamePolicy.fromEmail(email);
        for (int attempt = 0; attempt < 12; attempt++) {
            String username = attempt == 0
                    ? baseUsername
                    : UsernamePolicy.collisionCandidate(baseUsername, randomUsernameSuffix());
            String normalizedUsername = UsernamePolicy.normalize(username);
            if (users.existsByNormalizedUsername(normalizedUsername)) continue;
            try {
                return users.save(UserAccount.createUser(
                        username, normalizedUsername, email, normalizedEmail, clock.instant()));
            } catch (DataIntegrityViolationException exception) {
                if (users.existsByNormalizedEmail(normalizedEmail)) {
                    return users.findByNormalizedEmail(normalizedEmail).orElseThrow();
                }
            }
        }
        throw new ConflictException(
                "username_generation_failed", "No se pudo reservar un username para la cuenta.");
    }

    private void issueMagicLink(UserAccount user) {
        Instant now = clock.instant();
        tokens.invalidateUnconsumedForUser(user.id(), now);
        String rawToken = newRawToken();
        tokens.save(IdentityToken.issue(user.id(), hashToken(rawToken), now.plus(magicLinkTtl), now));
        events.magicLinkRequested(user, rawToken);
    }

    private IdentityToken requireUsableToken(String rawToken, boolean forUpdate) {
        String hash = hashToken(rawToken);
        IdentityToken token = (forUpdate ? tokens.findByHashForUpdate(hash) : tokens.findByHash(hash))
                .orElseThrow(this::invalidToken);
        if (token.consumedAt() != null) throw usedToken();
        if (!token.expiresAt().isAfter(clock.instant())) throw expiredToken();
        return token;
    }

    private BadRequestException invalidToken() {
        return new BadRequestException("magic_link_invalid", "El enlace de acceso no es válido.");
    }

    private GoneException expiredToken() {
        return new GoneException("magic_link_expired", "El enlace de acceso ha caducado.");
    }

    private GoneException usedToken() {
        return new GoneException("magic_link_used", "El enlace de acceso ya se ha utilizado.");
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
}
