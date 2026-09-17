package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;

/**
 * Exige una identidad administrativa de cuenta y utiliza su UUID estable para auditoría y
 * peticiones internas.
 *
 * @see es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal
 * @see es.ubu.batchdownloader.admin.AdminAuditService
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
 */
final class AdminActor {
    /**
     * Impide instancias de la resolución estática del actor administrativo.
     */
    private AdminActor() {}

    /**
     * Extrae el UUID del principal administrativo y rechaza una identidad ausente.
     *
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return UUID textual del actor.
     * @throws es.ubu.batchdownloader.common.UnauthorizedException si ya no existe el principal de
     *     la sesión.
     */
    static String require(AccountPrincipal principal) {
        if (principal == null) {
            throw new UnauthorizedException("unauthorized", "La sesión administrativa ya no es válida.");
        }
        return principal.userId().toString();
    }
}
