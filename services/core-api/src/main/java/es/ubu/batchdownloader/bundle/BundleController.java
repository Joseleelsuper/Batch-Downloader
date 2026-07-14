package es.ubu.batchdownloader.bundle;

import es.ubu.batchdownloader.admin.AdminAuditService;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleDetails;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSearchResponse;
import es.ubu.batchdownloader.bundle.BundleDtos.BundleSummary;
import es.ubu.batchdownloader.bundle.BundleDtos.UpsertBundleRequest;
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

@RestController
public class BundleController {
    private final BundleRepository bundles;
    private final AdminAuditService audit;

    public BundleController(BundleRepository bundles, AdminAuditService audit) {
        this.bundles = bundles;
        this.audit = audit;
    }

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

    @GetMapping({"/api/v1/bundles/{bundleId}", "/api/bundles/{bundleId}"})
    public BundleDetails getBundle(@PathVariable String bundleId, Authentication authentication) {
        return bundles.details(bundleId, actor(authentication), isAdmin(authentication));
    }

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

    @PostMapping("/api/admin/bundles")
    @ResponseStatus(HttpStatus.CREATED)
    public BundleDetails createBundle(
            @Valid @RequestBody UpsertBundleRequest request,
        Principal principal) {
        BundleDetails created = bundles.create(request, actor(principal));
        audit.record(actor(principal), "bundle.create", "bundle", created.id(), null);
        return created;
    }

    @PatchMapping("/api/admin/bundles/{bundleId}")
    public BundleDetails updateBundle(
            @PathVariable String bundleId,
            @Valid @RequestBody UpsertBundleRequest request,
            Principal principal) {
        BundleDetails updated = bundles.update(bundleId, request);
        audit.record(actor(principal), "bundle.update", "bundle", updated.id(), null);
        return updated;
    }

    @DeleteMapping("/api/admin/bundles/{bundleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBundle(@PathVariable String bundleId, Principal principal) {
        bundles.delete(bundleId);
        audit.record(actor(principal), "bundle.delete", "bundle", bundleId, null);
    }

    private String actor(Principal principal) {
        return principal == null ? null : principal.getName();
    }

    private boolean isAdmin(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }
}
