package es.ubu.batchdownloader.identity.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

/** Construcción única de la autenticación local persistida en Spring Session. */
final class AccountAuthentication {
    private AccountAuthentication() {}

    static UsernamePasswordAuthenticationToken authenticated(
            AccountPrincipal principal, HttpServletRequest request) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        return authentication;
    }
}
