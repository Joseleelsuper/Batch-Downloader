package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Resuelve el principal UUID de una sesión contra la cuenta actual y rechaza identidades
 * desconocidas, eliminadas o deshabilitadas.
 *
 * @see es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal
 * @see es.ubu.batchdownloader.identity.application.port.UserAccountStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Component
public class CurrentAccount {
    private final UserAccountStore users;

    /**
     * Conecta la consulta de cuentas para comprobar que la identidad de sesión sigue siendo válida.
     *
     * @param users Persistencia de cuentas y consultas de unicidad de correo y nombre normalizados.
     */
    public CurrentAccount(UserAccountStore users) {
        this.users = users;
    }

    /**
     * Exige sesión autenticada con AccountPrincipal y vuelve a cargar una cuenta habilitada por su
     * UUID.
     *
     * @param authentication Sesión autenticada que debe contener un AccountPrincipal reconocible.
     * @return cuenta vigente de la sesión.
     * @throws es.ubu.batchdownloader.common.UnauthorizedException si falta sesión, el principal es
     *     incompatible o la cuenta ya no está habilitada.
     */
    public UserAccount require(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            throw new UnauthorizedException("unauthorized", "Debes iniciar sesión.");
        }
        if (!(authentication.getPrincipal() instanceof AccountPrincipal account)) {
            throw new UnauthorizedException("unauthorized", "La sesión ya no es válida.");
        }
        return users.findById(account.userId())
                .filter(UserAccount::enabled)
                .orElseThrow(() -> new UnauthorizedException("unauthorized", "La sesión ya no es válida."));
    }

    /**
     * Permite visitantes sin sesión y exige validez actual para los que se presentan autenticados.
     *
     * @param authentication Sesión autenticada que debe contener un AccountPrincipal reconocible.
     * @return UUID canónico o null para visitantes anónimos.
     * @throws es.ubu.batchdownloader.common.UnauthorizedException si la sesión declarada no puede
     *     resolverse a una cuenta habilitada.
     */
    public UUID optionalUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) return null;
        return require(authentication).id();
    }
}
