package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Traduce sesiones nuevas y heredadas a una cuenta canónica. */
@Component
public class CurrentAccount {
    private final UserAccountStore users;

    public CurrentAccount(UserAccountStore users) {
        this.users = users;
    }

    public UserAccount require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new UnauthorizedException("unauthorized", "Debes iniciar sesión.");
        }
        if (authentication.getPrincipal() instanceof AccountPrincipal account) {
            return users.findById(account.userId()).filter(UserAccount::enabled)
                    .orElseThrow(() -> new UnauthorizedException("unauthorized", "La sesión ya no es válida."));
        }
        String legacyName = authentication.getName();
        return users.findByNormalizedUsername(legacyName.strip().toLowerCase(Locale.ROOT))
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new UnauthorizedException("unauthorized", "La sesión ya no es válida."));
    }

    public UUID optionalUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) return null;
        return require(authentication).id();
    }
}
