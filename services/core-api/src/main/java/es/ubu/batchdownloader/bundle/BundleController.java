package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.admin.AdminAuditService;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSearchResponse;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import es.ubu.batchdownloader.common.UnauthorizedException;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone listados y detalles de bundles y operaciones administrativas auditadas, delegando
 * visibilidad y selección a los repositorios.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.bundle.BundleRepository
 * @see es.ubu.batchdownloader.bundle.BundleAccessPolicy
 * @see es.ubu.batchdownloader.admin.AdminAuditService
 * @since 0.1.0
 * @version 0.1.0
 * @category Bundles
 */
@RestController
public class BundleController {
    /**
     * Estado {@code bundles} mantenido por {@code BundleController}.
     */
    private final BundleRepository bundles;
    /**
     * Estado {@code audit} mantenido por {@code BundleController}.
     */
    private final AdminAuditService audit;

    /**
     * Conecta las operaciones sobre bundles y el registro de acciones administrativas.
     *
     * @param bundles Fachada de consulta y escritura de bundles con sus políticas de visibilidad.
     * @param audit Registro de acciones administrativas con UUID de actor y recurso.
     */
    public BundleController(BundleRepository bundles, AdminAuditService audit) {
        this.bundles = bundles;
        this.audit = audit;
    }

    /**
     * Pagina los bundles públicos u oficiales con filtro de tipo y orden, acotando tamaño y número
     * de página.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @param sort stars prioriza estrellas y fecha; cualquier otro valor ordena por actualización
     *     descendente.
     * @return listado público con total antes de paginar.
     */
    @GetMapping("/api/v1/bundles")
    public BundleSearchResponse listBundles(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int pageSize,
            @RequestParam(defaultValue = "updated") String sort) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 60));
        List<BundleSummary> data = bundles.list(type, sort, safePage, safePageSize);
        return new BundleSearchResponse(data, safePage, safePageSize, bundles.count(type));
    }

    /**
     * Consulta un UUID o slug aplicando visibilidad pública, propiedad o acceso administrativo.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return detalle accesible.
     * @throws es.ubu.batchdownloader.common.NotFoundException si no existe o su visibilidad no
     *     permite consultarlo.
     */
    @GetMapping("/api/v1/bundles/{bundleId}")
    public BundleDetails getBundle(@PathVariable String bundleId, Authentication authentication) {
        UUID viewerId = authentication != null
                        && authentication.getPrincipal() instanceof AccountPrincipal account
                ? account.userId()
                : null;
        return bundles.details(bundleId, viewerId, isAdmin(authentication));
    }

    /**
     * Pagina bundles de cualquier visibilidad para una ruta previamente autorizada como
     * administrativa.
     *
     * @param type Tipo de bundle; null o blanco no filtra. La consulta pública trata community como
     *     community o user.
     * @param page Página numerada desde uno; los controladores acotan valores inferiores.
     * @param pageSize Elementos por página; los controladores limitan el rango a 1–60.
     * @param sort stars prioriza estrellas y fecha; cualquier otro valor ordena por actualización
     *     descendente.
     * @return listado administrativo con total correspondiente a su filtro.
     */
    @GetMapping("/api/v1/admin/bundles")
    public BundleSearchResponse listAdminBundles(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "updated") String sort) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.max(1, Math.min(pageSize, 60));
        return new BundleSearchResponse(
                bundles.listForAdministration(type, sort, safePage, safePageSize),
                safePage,
                safePageSize,
                bundles.countForAdministration(type));
    }

    /**
     * Crea el bundle con el UUID del administrador como propietario y registra bundle.create.
     *
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return 201 con el detalle creado.
     */
    @PostMapping("/api/v1/admin/bundles")
    @ResponseStatus(HttpStatus.CREATED)
    public BundleDetails createBundle(
            @Valid @RequestBody UpsertBundleRequest request,
            Authentication authentication) {
        AccountPrincipal account = account(authentication);
        BundleDetails created = bundles.create(request, account.userId());
        audit.record(account.userId().toString(), "bundle.create", "bundle", created.id(), null);
        return created;
    }

    /**
     * Guarda la edición administrativa y registra su actor y el UUID del bundle modificado.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param request Datos validados del bundle y su selección; las escrituras personales incluyen
     *     control de versión.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return detalle actualizado.
     */
    @PatchMapping("/api/v1/admin/bundles/{bundleId}")
    public BundleDetails updateBundle(
            @PathVariable String bundleId,
            @Valid @RequestBody UpsertBundleRequest request,
            Authentication authentication) {
        BundleDetails updated = bundles.update(bundleId, request);
        audit.record(account(authentication).userId().toString(),
                "bundle.update", "bundle", updated.id(), null);
        return updated;
    }

    /**
     * Elimina el bundle y registra bundle.delete; la ruta devuelve 204 al completarse.
     *
     * @param bundleId UUID del bundle, o su UUID textual o slug cuando así lo exige la ruta
     *     pública.
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     */
    @DeleteMapping("/api/v1/admin/bundles/{bundleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBundle(@PathVariable String bundleId, Authentication authentication) {
        bundles.delete(bundleId);
        audit.record(account(authentication).userId().toString(),
                "bundle.delete", "bundle", bundleId, null);
    }

    /**
     * Exige que la autenticación contenga la identidad UUID de una cuenta reconocida.
     *
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return principal de cuenta.
     * @throws es.ubu.batchdownloader.common.UnauthorizedException si falta la autenticación o su
     *     principal no es compatible.
     */
    private AccountPrincipal account(Authentication authentication) {
        if (authentication != null
                && authentication.getPrincipal() instanceof AccountPrincipal account) {
            return account;
        }
        throw new UnauthorizedException("unauthorized", "La sesión ya no es válida.");
    }

    /**
     * Busca la autoridad ROLE_ADMIN en la autenticación recibida.
     *
     * @param authentication Autenticación de Spring; el control de rutas exige el rol
     *     correspondiente.
     * @return true si existe esa autoridad.
     */
    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
