package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;

/** Obtiene el UUID canónico de la cuenta que ejecuta una operación administrativa. */
final class AdminActor {
    private AdminActor() {}

    static String require(AccountPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("unauthorized", "La sesión administrativa ya no es válida.");
        }
        return principal.userId().toString();
    }
}
