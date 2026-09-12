package es.ubu.batchdownloader.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

/**
 * Construye autenticación de sesión a partir de un principal ya comprobado y los detalles de la
 * petición HTTP.
 *
 * @see es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
final class AccountAuthentication {
    /**
     * Impide instancias del constructor estático de autenticaciones.
     */
    private AccountAuthentication() {}

    /**
     * Crea autenticación sin contraseña con las autoridades del principal y los detalles remotos de
     * la petición.
     *
     * @param principal Identidad de cuenta con UUID canónico, nombre visible y rol.
     * @param request Solicitud HTTP cuyos detalles se asocian a la autenticación.
     * @return autenticación lista para persistirse en el contexto de sesión.
     */
    static UsernamePasswordAuthenticationToken authenticated(
            AccountPrincipal principal, HttpServletRequest request) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }
}
