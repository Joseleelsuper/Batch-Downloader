package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.common.ForbiddenException;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.util.Locale;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Autentica por credenciales distintas sin mezclar usuarios y administradores. */
@Component
public class AccountAuthenticator {
    private static final String DUMMY_PASSWORD = "not-a-real-account-password";

    private final UserAccountStore users;
    private final PasswordEncoder passwords;
    private final String dummyHash;

    AccountAuthenticator(UserAccountStore users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
        this.dummyHash = passwords.encode(DUMMY_PASSWORD);
    }

    public Authentication authenticateUser(String email, String rawPassword) {
        String normalizedEmail = email.strip().toLowerCase(Locale.ROOT);
        UserAccount account = users.findByNormalizedEmail(normalizedEmail).orElse(null);
        String encoded = account != null && account.hasPassword() ? account.passwordHash() : dummyHash;
        boolean matches = passwords.matches(rawPassword, encoded);
        if (!matches || account == null || !account.enabled() || !account.hasPassword()
                || account.role() != UserRole.USER) {
            throw invalidCredentials();
        }
        if (!account.emailVerified()) {
            throw new ForbiddenException(
                    "email_not_verified", "Debes verificar tu correo antes de iniciar sesión.");
        }
        return authenticated(account);
    }

    public Authentication authenticateAdmin(String username, String rawPassword) {
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        UserAccount account = users.findByNormalizedUsername(normalized).orElse(null);
        String encoded = account != null && account.hasPassword() ? account.passwordHash() : dummyHash;
        boolean matches = passwords.matches(rawPassword, encoded);
        if (!matches || account == null || !account.enabled() || !account.hasPassword()
                || account.role() != UserRole.ADMIN) {
            throw invalidCredentials();
        }
        return authenticated(account);
    }

    public Authentication authenticated(UserAccount account) {
        AccountPrincipal principal = AccountPrincipal.from(account);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
    }

    private UnauthorizedException invalidCredentials() {
        return new UnauthorizedException("invalid_credentials", "Credenciales incorrectas.");
    }
}
