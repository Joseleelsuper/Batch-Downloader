package es.ubu.batchdownloader.identity.api;

import es.ubu.batchdownloader.bundle.UserBundleRepository;
import es.ubu.batchdownloader.identity.api.AccountDtos.AccountDashboard;
import es.ubu.batchdownloader.identity.api.AccountDtos.DownloadHistoryPage;
import es.ubu.batchdownloader.identity.application.AccountProfileService;
import es.ubu.batchdownloader.identity.application.IdentityService;
import es.ubu.batchdownloader.identity.application.IdentityView;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.identity.infrastructure.persistence.AccountOverviewRepository;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrega perfil, historial y panel personal y actualiza el nombre de la sesión conservando el UUID
 * de su propietario.
 *
 * @see es.ubu.batchdownloader.identity.infrastructure.security.CurrentAccount
 * @see es.ubu.batchdownloader.identity.application.AccountProfileService
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.AccountOverviewRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@RestController
@RequestMapping("/api/v1/users/me")
public class AccountController {
    private final CurrentAccount currentAccount;
    private final IdentityService identities;
    private final AccountProfileService profiles;
    private final AccountOverviewRepository overview;
    private final UserBundleRepository bundles;
    private final SecurityContextRepository securityContexts;

    /**
     * Conecta autorización de cuenta, perfil, historial, bundles y persistencia del contexto de
     * sesión.
     *
     * @param currentAccount Resolución de la sesión por UUID que vuelve a comprobar que la cuenta
     *     está habilitada.
     * @param identities Casos de uso de identidad que conservan el UUID y las restricciones de
     *     unicidad.
     * @param profiles Caso de uso que modifica el nombre visible conservando su identidad y
     *     propiedad de recursos.
     * @param overview Consultas del historial de descargas y los contadores personales.
     * @param bundles Consulta paginada de bundles pertenecientes a la cuenta.
     * @param securityContexts Persistencia del contexto de seguridad en la sesión HTTP.
     */
    public AccountController(
            CurrentAccount currentAccount,
            IdentityService identities,
            AccountProfileService profiles,
            AccountOverviewRepository overview,
            UserBundleRepository bundles,
            SecurityContextRepository securityContexts) {
        this.currentAccount = currentAccount;
        this.identities = identities;
        this.profiles = profiles;
        this.overview = overview;
        this.bundles = bundles;
        this.securityContexts = securityContexts;
    }

    /**
     * Recupera los datos actuales de la cuenta habilitada asociada a la sesión.
     *
     * @param authentication Autenticación actual de Spring, o null si el visitante no tiene sesión.
     * @return identidad sin credenciales.
     */
    @GetMapping
    IdentityView me(Authentication authentication) {
        return identities.findById(currentAccount.require(authentication).id());
    }

    /**
     * Guarda el nuevo nombre y sustituye el principal de la sesión conservando UUID, rol y detalles
     * de autenticación.
     *
     * @param request Cuerpo validado de la operación o solicitud HTTP cuando la firma utiliza
     *     HttpServletRequest.
     * @param authentication Autenticación actual de Spring, o null si el visitante no tiene sesión.
     * @param servletRequest Solicitud HTTP de la que se obtiene dirección remota o sesión.
     * @param servletResponse Respuesta donde se conservan los cambios de sesión y sus cookies.
     * @return vista del perfil actualizado.
     */
    @PatchMapping
    IdentityView update(
            @Valid @RequestBody UsernameRequest request,
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        UserAccount current = currentAccount.require(authentication);
        IdentityView changed = profiles.changeUsername(current.id(), request.username());
        AccountPrincipal principal = new AccountPrincipal(current.id(), changed.username(), current.role());
        var replacement = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());
        replacement.setDetails(authentication.getDetails());
        var context = SecurityContextHolder.getContext();
        context.setAuthentication(replacement);
        securityContexts.saveContext(context, servletRequest, servletResponse);
        return changed;
    }

    /**
     * Consulta el historial del propietario y acota la página a un mínimo de uno y su tamaño a
     * 1–60.
     *
     * @param page Página solicitada, numerada desde uno.
     * @param pageSize Número de filas solicitado por página; el controlador lo limita a 1–60.
     * @param authentication Autenticación actual de Spring, o null si el visitante no tiene sesión.
     * @return página personal con total independiente del tamaño solicitado.
     */
    @GetMapping("/downloads")
    DownloadHistoryPage downloads(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            Authentication authentication) {
        UserAccount account = currentAccount.require(authentication);
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(60, pageSize));
        return new DownloadHistoryPage(
                overview.downloads(account.id(), safePage, safePageSize), safePage, safePageSize,
                overview.downloadCount(account.id()));
    }

    /**
     * Combina identidad, contadores, diez descargas recientes y seis bundles de la misma cuenta.
     *
     * @param authentication Autenticación actual de Spring, o null si el visitante no tiene sesión.
     * @return datos del panel personal bajo una identidad UUID única.
     */
    @GetMapping("/dashboard")
    AccountDashboard dashboard(Authentication authentication) {
        UserAccount account = currentAccount.require(authentication);
        return new AccountDashboard(
                identities.findById(account.id()),
                overview.counts(account.id()),
                overview.downloads(account.id(), 1, 10),
                bundles.list(account.id(), 1, 6));
    }

    /**
     * Transporta el nombre visible propuesto; la política de aplicación completa su validación de
     * formato y unicidad.
     *
     * @param username Nombre visible de la cuenta, distinto de su UUID de identidad.
     * @since 0.1.0
     * @version 0.1.0
     * @category Identidad
     */
    record UsernameRequest(@NotBlank @Size(min = 3, max = 40) String username) {}
}
