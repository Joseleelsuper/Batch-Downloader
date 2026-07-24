package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.IdentityView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/auth")
public class IdentityController {
    private final IdentityService identities;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContexts;

    public IdentityController(
            IdentityService identities,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContexts) {
        this.identities = identities;
        this.authenticationManager = authenticationManager;
        this.securityContexts = securityContexts;
    }

    @PostMapping("/register")
    ResponseEntity<IdentityView> register(@Valid @RequestBody RegisterRequest request) {
        IdentityView created = identities.register(request.username(), request.email(), request.password());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    @PostMapping("/login")
    IdentityView login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, servletRequest, servletResponse);
        return identities.findByUsername(authentication.getName());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    ResponseEntity<IdentityView> me(Principal principal) {
        if (principal == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(identities.findByUsername(principal.getName()));
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

    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        identities.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        identities.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/preferences")
    IdentityView updatePreferences(@Valid @RequestBody PreferencesRequest request, Principal principal) {
        return identities.updateNotificationPreference(principal.getName(), request.notifyOnJobCompletion());
    }

    record RegisterRequest(
            @NotBlank @Size(min = 3, max = 80) String username,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    record TokenRequest(@NotBlank @Size(max = 256) String token) {}
    record PasswordResetRequest(@NotBlank @Email @Size(max = 320) String email) {}
    record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 256) String token,
            @NotBlank @Size(min = 12, max = 128) String password) {}
    record PreferencesRequest(boolean notifyOnJobCompletion) {}
    record CsrfResponse(String headerName, String parameterName, String token) {}
}
