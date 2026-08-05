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

/**
 * Expone las operaciones HTTP gestionadas por {@code IdentityController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
@Validated
@RequestMapping("/api/v1/auth")
public class IdentityController {
    /**
     * Estado {@code identities} mantenido por {@code IdentityController}.
     */
    private final IdentityService identities;
    /**
     * Estado {@code authenticationManager} mantenido por {@code IdentityController}.
     */
    private final AuthenticationManager authenticationManager;
    /**
     * Estado {@code securityContexts} mantenido por {@code IdentityController}.
     */
    private final SecurityContextRepository securityContexts;
    /** Límite de peticiones costosas de autenticación. */
    private final AuthRateLimiter rateLimiter;

    /**
     * Inicializa una instancia de {@code IdentityController}.
     *
     * @param identities Valor de {@code identities} utilizado por la operación.
     * @param authenticationManager Valor de {@code authenticationManager} utilizado por la
     *     operación.
     * @param securityContexts Valor de {@code securityContexts} utilizado por la operación.
     */
    public IdentityController(
            IdentityService identities,
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContexts,
            AuthRateLimiter rateLimiter) {
        this.identities = identities;
        this.authenticationManager = authenticationManager;
        this.securityContexts = securityContexts;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Ejecuta la operación {@code register}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code register}.
     */
    @PostMapping("/register")
    ResponseEntity<IdentityView> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletRequest servletRequest) {
        rateLimiter.registration(clientIp(servletRequest), request.email());
        IdentityView created = identities.register(request.username(), request.email(), request.password());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(created);
    }

    /**
     * Ejecuta la operación {@code login}.
     *
     * @param request Solicitud recibida por la operación.
     * @param servletRequest Valor de {@code servletRequest} utilizado por la operación.
     * @param servletResponse Valor de {@code servletResponse} utilizado por la operación.
     * @return Resultado producido por {@code login}.
     */
    @PostMapping("/login")
    IdentityView login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        rateLimiter.login(clientIp(servletRequest), request.username());
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, servletRequest, servletResponse);
        return identities.findByUsername(authentication.getName());
    }

    /**
     * Ejecuta la operación {@code logout}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code logout}.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request) {
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    /**
     * Ejecuta la operación {@code me}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code me}.
     */
    @GetMapping("/me")
    ResponseEntity<IdentityView> me(Principal principal) {
        if (principal == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(identities.findByUsername(principal.getName()));
    }

    /**
     * Ejecuta la operación {@code csrf}.
     *
     * @param token Token utilizado para autorizar o correlacionar la operación.
     * @return Resultado producido por {@code csrf}.
     */
    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    /**
     * Ejecuta la operación {@code confirmEmail}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code confirmEmail}.
     */
    @PostMapping("/email-verification/confirm")
    ResponseEntity<Void> confirmEmail(@Valid @RequestBody TokenRequest request) {
        identities.confirmEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    /**
     * Ejecuta la operación {@code requestPasswordReset}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code requestPasswordReset}.
     */
    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody PasswordResetRequest request,
            HttpServletRequest servletRequest) {
        rateLimiter.reset(clientIp(servletRequest), request.email());
        identities.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    /**
     * Ejecuta la operación {@code confirmPasswordReset}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code confirmPasswordReset}.
     */
    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        identities.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updatePreferences}.
     *
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code updatePreferences}.
     */
    @PatchMapping("/preferences")
    IdentityView updatePreferences(@Valid @RequestBody PreferencesRequest request, Principal principal) {
        return identities.updateNotificationPreference(principal.getName(), request.notifyOnJobCompletion());
    }

    /**
     * Obtiene la IP ya normalizada por la estrategia de cabeceras reenviadas de Spring.
     *
     * @param request Solicitud HTTP.
     * @return Dirección utilizada por los límites locales.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Representa los datos inmutables de {@code RegisterRequest}.
     *
     * @param username Valor de {@code username} incluido en el record.
     * @param email Valor de {@code email} incluido en el record.
     * @param password Valor de {@code password} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record RegisterRequest(
            @NotBlank @Size(min = 3, max = 80) String username,
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(min = 12, max = 128) String password) {}

    /**
     * Representa los datos inmutables de {@code LoginRequest}.
     *
     * @param username Valor de {@code username} incluido en el record.
     * @param password Valor de {@code password} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    /**
     * Representa los datos inmutables de {@code TokenRequest}.
     *
     * @param token Valor de {@code token} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record TokenRequest(@NotBlank @Size(max = 256) String token) {}
    /**
     * Representa los datos inmutables de {@code PasswordResetRequest}.
     *
     * @param email Valor de {@code email} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record PasswordResetRequest(@NotBlank @Email @Size(max = 320) String email) {}
    /**
     * Representa los datos inmutables de {@code PasswordResetConfirmRequest}.
     *
     * @param token Valor de {@code token} incluido en el record.
     * @param password Valor de {@code password} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 256) String token,
            @NotBlank @Size(min = 12, max = 128) String password) {}
    /**
     * Representa los datos inmutables de {@code PreferencesRequest}.
     *
     * @param notifyOnJobCompletion Valor de {@code notifyOnJobCompletion} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record PreferencesRequest(boolean notifyOnJobCompletion) {}
    /**
     * Representa los datos inmutables de {@code CsrfResponse}.
     *
     * @param headerName Valor de {@code headerName} incluido en el record.
     * @param parameterName Valor de {@code parameterName} incluido en el record.
     * @param token Valor de {@code token} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record CsrfResponse(String headerName, String parameterName, String token) {}
}
