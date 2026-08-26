package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.IdentityView;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountAuthenticator;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API pública de cuentas de usuario. */
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

    @PostMapping("/register")
    ResponseEntity<IdentityView> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        rateLimiter.registration(clientIp(servletRequest), request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(identities.register(request.email(), request.password()));
    }

    @PostMapping("/login")
    IdentityView login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        rateLimiter.login(clientIp(servletRequest), request.email());
        Authentication authentication = authenticator.authenticateUser(request.email(), request.password());
        saveAuthentication(authentication, servletRequest, servletResponse);
        return identities.findById(currentAccount.require(authentication).id());
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

    @PostMapping("/email-verification/confirm")
    ResponseEntity<Void> confirmEmail(@Valid @RequestBody TokenRequest request) {
        identities.confirmEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/email-verification/resend")
    ResponseEntity<Void> resendVerification(
            @Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        rateLimiter.verification(clientIp(servletRequest), request.email());
        identities.resendEmailVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        rateLimiter.reset(clientIp(servletRequest), request.email());
        identities.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        identities.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/preferences")
    IdentityView updatePreferences(
            @Valid @RequestBody PreferencesRequest request, Authentication authentication) {
        return identities.updateNotificationPreference(
                currentAccount.require(authentication).id(), request.notifyOnJobCompletion());
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

    record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {}
    record LoginRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 128) String password) {}
    record TokenRequest(@NotBlank @Size(max = 256) String token) {}
    record EmailRequest(@NotBlank @Email @Size(max = 320) String email) {}
    record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 256) String token,
            @NotBlank @Size(min = 12, max = 128) String password) {}
    record PreferencesRequest(boolean notifyOnJobCompletion) {}
    record CsrfResponse(String headerName, String parameterName, String token) {}
}
