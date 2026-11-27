package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserRole;
import java.security.Principal;
import org.springframework.security.core.Authentication;

/** Contrasta la versión autenticada con las credenciales vigentes en todas las réplicas. */
public class AdminSessionVersion {
    private final UserAccountStore users;

    public AdminSessionVersion(UserAccountStore users) {
        this.users = users;
    }

    public boolean isCurrent(Principal principal) {
        if (!(principal instanceof Authentication authentication)
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal account)
                || account.role() != UserRole.ADMIN
                || account.credentialsVersion() < 1) {
            return false;
        }
        return users.findById(account.userId())
                .filter(user -> user.enabled() && user.role() == UserRole.ADMIN
                        && user.credentialsVersion() == account.credentialsVersion())
                .isPresent();
    }
}
