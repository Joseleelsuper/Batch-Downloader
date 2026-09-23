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

/**
 * Gestiona sesiones administrativas mediante nombre de usuario, comprobación de rol y cuotas de
 * acceso.
 *
 * @see es.ubu.batchdownloader.identity.infrastructure.security.AccountAuthenticator
 * @see es.ubu.batchdownloader.identity.api.AuthRateLimiter
 * @see es.ubu.batchdownloader.identity.infrastructure.security.SecurityConfig
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@RestController
@RequestMapping("/api/v1/admin/auth")
public class AdminIdentityController {
    private final IdentityService identities;
    private final AccountAuthenticator authenticator;
    private final CurrentAccount currentAccount;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessions;
    private final AuthRateLimiter rateLimiter;

    /**
     * Conecta verificación administrativa y renovación y persistencia de la sesión.
     *
     * @param identities Casos de uso de identidad que conservan el UUID y las restricciones de
     *     unicidad.
     * @param authenticator Verificador de credenciales que distingue cuentas USER y ADMIN.
     * @param currentAccount Resolución de la sesión por UUID que vuelve a comprobar que la cuenta
     *     está habilitada.
     * @param contexts Repositorio donde se guarda la autenticación de la nueva sesión.
     * @param sessions Estrategia que renueva identificador de sesión y token CSRF al entrar.
     * @param rateLimiter Cuota exclusiva para intentos de acceso administrativo.
     */
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

    /**
     * Consume cuota, exige credenciales de una cuenta ADMIN habilitada y guarda un nuevo contexto
     * autenticado.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param servletRequest Solicitud HTTP de la que se obtiene dirección remota o sesión.
     * @param servletResponse Respuesta donde se conservan los cambios de sesión y sus cookies.
     * @return identidad administrativa de la sesión creada.
     */
    @PostMapping("/login")
    IdentityView login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        rateLimiter.adminLogin(servletRequest.getRemoteAddr(), request.username());
        Authentication authentication = authenticator.authenticateAdmin(request.username(), request.password());
        sessions.onAuthentication(authentication, servletRequest, servletResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, servletRequest, servletResponse);
        return identities.findById(currentAccount.require(authentication).id());
    }

    /**
     * Recupera la cuenta administrativa vigente de una sesión previamente autorizada por Spring
     * Security.
     *
     * @param authentication Autenticación actual de Spring, o null si el visitante no tiene sesión.
     * @return vista actual de identidad.
     */
    @GetMapping("/me")
    IdentityView me(Authentication authentication) {
        return identities.findById(currentAccount.require(authentication).id());
    }

    /**
     * Invalida la sesión administrativa y limpia su contexto de seguridad.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param response Respuesta HTTP en la que se comunica o invalida la sesión.
     * @return 204 sin cuerpo.
     */
    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler().logout(
                request, response, SecurityContextHolder.getContext().getAuthentication());
        return ResponseEntity.noContent().build();
    }

    /**
     * Transporta nombre y contraseña del acceso administrativo; el verificador comprueba rol y
     * límites de BCrypt.
     *
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @param password Contraseña recibida; los límites se cuentan en puntos de código y bytes
     *     UTF-8.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record LoginRequest(@NotBlank String username, @NotBlank String password) {}
}
