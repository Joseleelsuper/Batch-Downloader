package es.ubu.batchdownloader.identity.infrastructure.security;

import es.ubu.batchdownloader.identity.api.OAuthLoginController;
import es.ubu.batchdownloader.identity.application.GoogleOauthAccountService;
import es.ubu.batchdownloader.identity.application.GoogleOauthAccountService.OauthLoginException;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/** Convierte la autenticación OIDC en la sesión local UUID de la aplicación. */
@Component
public class GoogleOAuthSuccessHandler implements AuthenticationSuccessHandler {
    private final GoogleOauthAccountService accounts;
    private final SessionAuthenticationStrategy sessions;
    private final SecurityContextRepository securityContexts;

    public GoogleOAuthSuccessHandler(
            GoogleOauthAccountService accounts,
            SessionAuthenticationStrategy sessions,
            SecurityContextRepository securityContexts) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.securityContexts = securityContexts;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        try {
            if (!(authentication.getPrincipal() instanceof OidcUser oidc)) {
                throw new OauthLoginException("oauth_claims_invalid");
            }
            Boolean verified = oidc.getClaim("email_verified");
            UserAccount account = accounts.resolve(
                    oidc.getSubject(), oidc.getEmail(), Boolean.TRUE.equals(verified));
            AccountPrincipal principal = AccountPrincipal.from(account);
            var local = AccountAuthentication.authenticated(principal, request);

            sessions.onAuthentication(local, request, response);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(local);
            SecurityContextHolder.setContext(context);
            securityContexts.saveContext(context, request, response);

            HttpSession session = request.getSession(false);
            Object stored = session == null ? null
                    : session.getAttribute(OAuthLoginController.RETURN_TO_SESSION_ATTRIBUTE);
            if (session != null) {
                session.removeAttribute(OAuthLoginController.RETURN_TO_SESSION_ATTRIBUTE);
            }
            response.sendRedirect(OAuthLoginController.safeReturnTo(
                    stored instanceof String destination ? destination : null));
        } catch (OauthLoginException exception) {
            clear(request);
            response.sendRedirect("/login?oauthError=" + URLEncoder.encode(
                    exception.publicCode(), StandardCharsets.UTF_8));
        } catch (RuntimeException exception) {
            clear(request);
            response.sendRedirect("/login?oauthError=oauth_failed");
        }
    }

    private static void clear(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(OAuthLoginController.RETURN_TO_SESSION_ATTRIBUTE);
        }
    }
}
