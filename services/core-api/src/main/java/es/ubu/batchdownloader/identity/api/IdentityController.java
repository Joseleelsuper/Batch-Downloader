package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.IdentityView;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountAuthenticator;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Expone la solicitud y confirmación de enlaces de acceso y el perfil de la sesión. */
@RestController
@Validated
@RequestMapping("/api/v1/auth")
public class IdentityController {
    private final IdentityService identities;
    private final AccountAuthenticator authenticator;
    private final CurrentAccount currentAccount;
    private final SecurityContextRepository securityContexts;
    private final SessionAuthenticationStrategy sessionStrategy;
    private final AuthRateLimiter rateLimiter;

    public IdentityController(
            IdentityService identities,
            AccountAuthenticator authenticator,
            CurrentAccount currentAccount,
            SecurityContextRepository securityContexts,
            SessionAuthenticationStrategy sessionStrategy,
            AuthRateLimiter rateLimiter) {
        this.identities = identities;
        this.authenticator = authenticator;
        this.currentAccount = currentAccount;
        this.securityContexts = securityContexts;
        this.sessionStrategy = sessionStrategy;
        this.rateLimiter = rateLimiter;
    }

    /** Solicita un enlace de acceso con respuesta uniforme para cualquier correo válido. */
    @PostMapping("/magic-link/request")
    ResponseEntity<Void> requestMagicLink(
            @Valid @RequestBody MagicLinkRequest request, HttpServletRequest servletRequest) {
        rateLimiter.magicLinkRequest(clientIp(servletRequest), request.email());
        identities.requestMagicLink(request.email());
        return ResponseEntity.accepted().build();
    }

    /** Consume el enlace, rota la sesión y devuelve la identidad autenticada. */
    @PostMapping("/magic-link/confirm")
    IdentityView confirmMagicLink(
            @Valid @RequestBody TokenRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        rateLimiter.magicLinkConfirmation(clientIp(servletRequest));
        UserAccount user = identities.consumeMagicLink(request.token());
        Authentication authentication = authenticator.authenticated(user);
        saveAuthentication(authentication, servletRequest, servletResponse);
        return identities.view(user);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(
                request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    ResponseEntity<IdentityView> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(identities.findById(currentAccount.require(authentication).id()));
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    private void saveAuthentication(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    record MagicLinkRequest(@NotBlank @Email @Size(max = 254) String email) {}

    record TokenRequest(@NotBlank @Size(max = 256) String token) {}

    record CsrfResponse(String headerName, String parameterName, String token) {}
}
