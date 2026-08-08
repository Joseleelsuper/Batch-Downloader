package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.IdentityView;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountAuthenticator;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Login administrativo separado del acceso de cuentas públicas. */
@RestController
@RequestMapping("/api/admin/auth")
public class AdminIdentityController {
    private final IdentityService identities;
    private final AccountAuthenticator authenticator;
    private final CurrentAccount currentAccount;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final AuthRateLimiter rateLimiter;

    public AdminIdentityController(
            IdentityService identities,
            AccountAuthenticator authenticator,
            CurrentAccount currentAccount,
            SecurityContextRepository contexts,
            SessionAuthenticationStrategy sessions,
            AuthRateLimiter rateLimiter) {
        this.identities = identities;
        this.authenticator = authenticator;
        this.currentAccount = currentAccount;
        this.contexts = contexts;
        this.sessions = sessions;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/login")
    IdentityView login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        rateLimiter.login(servletRequest.getRemoteAddr(), request.username());
        Authentication authentication = authenticator.authenticateAdmin(request.username(), request.password());
        sessions.onAuthentication(authentication, servletRequest, servletResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, servletRequest, servletResponse);
        return identities.findById(currentAccount.require(authentication).id());
    }

    @GetMapping("/me")
    IdentityView me(Authentication authentication) {
        return identities.findById(currentAccount.require(authentication).id());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(
                request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
