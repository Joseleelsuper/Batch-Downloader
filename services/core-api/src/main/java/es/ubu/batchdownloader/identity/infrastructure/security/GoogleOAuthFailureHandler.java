package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.api.OAuthLoginController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/** Oculta detalles internos del proveedor en los fallos OIDC. */
@Component
public class GoogleOAuthFailureHandler implements AuthenticationFailureHandler {
    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(OAuthLoginController.RETURN_TO_SESSION_ATTRIBUTE);
        }
        response.sendRedirect("/login?oauthError=oauth_failed");
    }
}
