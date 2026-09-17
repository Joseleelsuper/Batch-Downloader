package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.common.ForbiddenException;
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

/**
 * Expone registro, acceso, recuperación, verificación y preferencias con cuotas, sesiones y CSRF
 * coherentes para el navegador.
 *
 * @see es.ubu.batchdownloader.identity.application.IdentityService
 * @see es.ubu.batchdownloader.identity.infrastructure.security.AccountAuthenticator
 * @see es.ubu.batchdownloader.identity.api.AuthRateLimiter
 * @see es.ubu.batchdownloader.identity.infrastructure.security.SecurityConfig
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
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

    /**
     * Conecta casos de identidad, comprobación de credenciales, cuotas y renovación del contexto de
     * sesión.
     *
     * @param identities Casos de uso de identidad que conservan el UUID y las restricciones de
     *     unicidad.
     * @param authenticator Verificador de credenciales que distingue cuentas USER y ADMIN.
     * @param currentAccount Resolución de la sesión por UUID que vuelve a comprobar que la cuenta
     *     está habilitada.
     * @param securityContexts Persistencia del contexto de seguridad en la sesión HTTP.
     * @param sessionStrategy Renovación del identificador de sesión y del token CSRF al
     *     autenticarse.
     * @param rateLimiter Cuotas separadas de acceso, registro, recuperación y reenvío de
     *     verificación.
     */
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

    /**
     * Consume cuota de registro antes de crear la cuenta y solicitar el correo de verificación.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param servletRequest Solicitud HTTP de la que se obtiene dirección remota o sesión.
     * @return 202 con la identidad todavía pendiente de verificar.
     */
    @PostMapping("/register")
    ResponseEntity<IdentityView> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        rateLimiter.registration(clientIp(servletRequest), request.email());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(identities.register(request.email(), request.password()));
    }

    /**
     * Consume cuota y autentica por correo; solo tras credenciales correctas con email_not_verified
     * intenta reenviar bajo una cuota separada y conserva ese mismo error.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param servletRequest Solicitud HTTP de la que se obtiene dirección remota o sesión.
     * @param servletResponse Respuesta donde se conservan los cambios de sesión y sus cookies.
     * @return identidad de la sesión creada tras superar autenticación y verificación.
     */
    @PostMapping("/login")
    IdentityView login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        rateLimiter.login(clientIp(servletRequest), request.email());
        Authentication authentication;
        try {
            authentication = authenticator.authenticateUser(request.email(), request.password());
        } catch (ForbiddenException exception) {
            if ("email_not_verified".equals(exception.code())
                    && rateLimiter.tryVerification(clientIp(servletRequest), request.email())) {
                identities.resendEmailVerification(request.email());
            }
            throw exception;
        }
        saveAuthentication(authentication, servletRequest, servletResponse);
        return identities.findById(currentAccount.require(authentication).id());
    }

    /**
     * Invalida la sesión y elimina su autenticación del contexto de seguridad.
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
     * Distingue al visitante anónimo de una sesión cuya cuenta todavía está habilitada.
     *
     * @param authentication Autenticación actual de Spring, o null si el visitante no tiene sesión.
     * @return 204 para visitantes sin sesión o 200 con la identidad actual.
     */
    @GetMapping("/me")
    ResponseEntity<IdentityView> me(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(identities.findById(currentAccount.require(authentication).id()));
    }

    /**
     * Expone los nombres de cabecera y parámetro y materializa el token CSRF de la sesión.
     *
     * @param token Token CSRF resuelto por Spring para esta solicitud.
     * @return datos que el cliente necesita para proteger sus mutaciones.
     */
    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getParameterName(), token.getToken());
    }

    /**
     * Consume el token de verificación y confirma el correo de su cuenta.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @return 204 después de guardar ambos cambios.
     */
    @PostMapping("/email-verification/confirm")
    ResponseEntity<Void> confirmEmail(@Valid @RequestBody TokenRequest request) {
        identities.confirmEmail(request.token());
        return ResponseEntity.noContent().build();
    }

    /**
     * Aplica la cuota de reenvío y solicita verificación con respuesta uniforme ante cuentas
     * inexistentes o ya verificadas.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param servletRequest Solicitud HTTP de la que se obtiene dirección remota o sesión.
     * @return 202 sin revelar si se ha emitido un mensaje.
     */
    @PostMapping("/email-verification/resend")
    ResponseEntity<Void> resendVerification(
            @Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        rateLimiter.verification(clientIp(servletRequest), request.email());
        identities.resendEmailVerification(request.email());
        return ResponseEntity.accepted().build();
    }

    /**
     * Aplica la cuota de recuperación y solicita el correo solo si la cuenta es elegible.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param servletRequest Solicitud HTTP de la que se obtiene dirección remota o sesión.
     * @return 202 uniforme sin revelar la existencia de la cuenta.
     */
    @PostMapping("/password-reset/request")
    ResponseEntity<Void> requestPasswordReset(
            @Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        rateLimiter.reset(clientIp(servletRequest), request.email());
        identities.requestPasswordReset(request.email());
        return ResponseEntity.accepted().build();
    }

    /**
     * Valida la nueva contraseña, consume el token de recuperación y revoca las sesiones
     * anteriores.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @return 204 tras completar el cambio de credenciales.
     */
    @PostMapping("/password-reset/confirm")
    ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        identities.resetPassword(request.token(), request.password());
        return ResponseEntity.noContent().build();
    }

    /**
     * Guarda la preferencia de avisos de descarga de la cuenta habilitada en la sesión.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param authentication Autenticación actual de Spring, o null si el visitante no tiene sesión.
     * @return vista de identidad con la preferencia actualizada.
     */
    @PatchMapping("/preferences")
    IdentityView updatePreferences(
            @Valid @RequestBody PreferencesRequest request, Authentication authentication) {
        return identities.updateNotificationPreference(
                currentAccount.require(authentication).id(), request.notifyOnJobCompletion());
    }

    /**
     * Renueva el identificador de sesión y CSRF y guarda la autenticación en un contexto nuevo.
     *
     * @param authentication Autenticación actual de Spring, o null si el visitante no tiene sesión.
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param response Respuesta HTTP en la que se comunica o invalida la sesión.
     */
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

    /**
     * Obtiene la dirección remota que el servidor utiliza para limitar las solicitudes de
     * identidad.
     *
     * @param request Solicitud HTTP observada por el servidor.
     * @return dirección remota de la conexión.
     */
    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * Recibe correo y contraseña para crear una cuenta; las reglas de contraseña se comprueban
     * antes de BCrypt.
     *
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param password Contraseña recibida; los límites se cuentan en puntos de código y bytes
     *     UTF-8.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank String password) {}
    /**
     * Recibe credenciales por correo; el acceso conserva compatibilidad con contraseñas antiguas
     * dentro de 72 bytes UTF-8.
     *
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @param password Contraseña recibida; los límites se cuentan en puntos de código y bytes
     *     UTF-8.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank String password) {}
    /**
     * Transporta un permiso de verificación no vacío y de hasta 256 caracteres.
     *
     * @param token Token opaco recibido en el enlace de verificación.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record TokenRequest(@NotBlank @Size(max = 256) String token) {}
    /**
     * Acota a 254 caracteres un correo sintácticamente válido para solicitar verificación o
     * recuperación.
     *
     * @param email Correo de la cuenta; se conserva recortado y se compara mediante su versión
     *     normalizada.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {}
    /**
     * Une el permiso de recuperación y la nueva contraseña que se validarán y consumirán en el
     * mismo flujo.
     *
     * @param token Token opaco de recuperación, no vacío y de hasta 256 caracteres.
     * @param password Contraseña recibida; los límites se cuentan en puntos de código y bytes
     *     UTF-8.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record PasswordResetConfirmRequest(
            @NotBlank @Size(max = 256) String token,
            @NotBlank String password) {}
    /**
     * Transporta la preferencia del propietario de recibir correo cuando terminan sus descargas.
     *
     * @param notifyOnJobCompletion Preferencia vigente de recibir correo cuando termina una
     *     descarga.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record PreferencesRequest(boolean notifyOnJobCompletion) {}
    /**
     * Indica cómo debe devolver el navegador el token de protección de sus mutaciones.
     *
     * @param headerName Cabecera que el cliente debe enviar en las mutaciones protegidas por CSRF.
     * @param parameterName Nombre alternativo del parámetro CSRF admitido por Spring.
     * @param token Valor CSRF de la sesión actual; no es un token de verificación de identidad.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record CsrfResponse(String headerName, String parameterName, String token) {}
}
