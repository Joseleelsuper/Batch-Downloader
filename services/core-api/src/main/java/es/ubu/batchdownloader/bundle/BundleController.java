package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.admin.AdminAuditService;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSearchResponse;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
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
 * Expone las operaciones HTTP gestionadas por {@code BundleController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
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
     * Inicializa una instancia de {@code BundleController}.
     *
     * @param bundles Valor de {@code bundles} utilizado por la operación.
     * @param audit Valor de {@code audit} utilizado por la operación.
     */
    public BundleController(BundleRepository bundles, AdminAuditService audit) {
        this.bundles = bundles;
        this.audit = audit;
    }

    /**
     * Enumera los elementos solicitados mediante {@code listBundles}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @return Resultado producido por {@code listBundles}.
     */
    @GetMapping({"/api/v1/bundles", "/api/bundles"})
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
     * Obtiene el resultado solicitado mediante {@code getBundle}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @param authentication Valor de {@code authentication} utilizado por la operación.
     * @return Resultado producido por {@code getBundle}.
     */
    @GetMapping({"/api/v1/bundles/{bundleId}", "/api/bundles/{bundleId}"})
    public BundleDetails getBundle(@PathVariable String bundleId, Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AccountPrincipal account) {
            return bundles.detailsForViewer(bundleId, account.userId(), isAdmin(authentication));
        }
        return bundles.details(bundleId, actor(authentication), isAdmin(authentication));
    }

    /**
     * Enumera los elementos solicitados mediante {@code listAdminBundles}.
     *
     * @param type Valor de {@code type} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @return Resultado producido por {@code listAdminBundles}.
     */
    @GetMapping("/api/admin/bundles")
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
     * Crea el recurso solicitado mediante {@code createBundle}.
     *
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code createBundle}.
     */
    @PostMapping("/api/admin/bundles")
    @ResponseStatus(HttpStatus.CREATED)
    public BundleDetails createBundle(
            @Valid @RequestBody UpsertBundleRequest request,
        Principal principal) {
        BundleDetails created = bundles.create(request, actor(principal));
        audit.record(actor(principal), "bundle.create", "bundle", created.id(), null);
        return created;
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updateBundle}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code updateBundle}.
     */
    @PatchMapping("/api/admin/bundles/{bundleId}")
    public BundleDetails updateBundle(
            @PathVariable String bundleId,
            @Valid @RequestBody UpsertBundleRequest request,
            Principal principal) {
        BundleDetails updated = bundles.update(bundleId, request);
        audit.record(actor(principal), "bundle.update", "bundle", updated.id(), null);
        return updated;
    }

    /**
     * Elimina el recurso solicitado mediante {@code deleteBundle}.
     *
     * @param bundleId Identificador de {@code bundle} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     */
    @DeleteMapping("/api/admin/bundles/{bundleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBundle(@PathVariable String bundleId, Principal principal) {
        bundles.delete(bundleId);
        audit.record(actor(principal), "bundle.delete", "bundle", bundleId, null);
    }

    /**
     * Ejecuta la operación {@code actor}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code actor}.
     */
    private String actor(Principal principal) {
        if (principal instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AccountPrincipal account) {
            return account.displayUsername();
        }
        return principal == null ? null : principal.getName();
    }

    /**
     * Indica si se cumple la condición mediante {@code isAdmin}.
     *
     * @param authentication Valor de {@code authentication} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
