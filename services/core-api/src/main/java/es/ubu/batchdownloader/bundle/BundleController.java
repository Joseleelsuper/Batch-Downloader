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

    @GetMapping("/api/bundles")
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

    @GetMapping("/api/bundles/{slug}")
    public BundleDetails getBundle(@PathVariable String slug) {
        return bundles.details(slug);
    }

    @GetMapping("/api/admin/bundles")
    public BundleSearchResponse listAdminBundles(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "updated") String sort) {
        return listBundles(type, page, pageSize, sort);
    }

    @PostMapping("/api/admin/bundles")
    @ResponseStatus(HttpStatus.CREATED)
    public BundleDetails createBundle(
            @Valid @RequestBody UpsertBundleRequest request,
            Principal principal) {
        BundleDetails created = bundles.create(request);
        audit.record(actor(principal), "bundle.create", "bundle", created.slug(), null);
        return created;
    }

    @PatchMapping("/api/admin/bundles/{slug}")
    public BundleDetails updateBundle(
            @PathVariable String slug,
            @Valid @RequestBody UpsertBundleRequest request,
            Principal principal) {
        BundleDetails updated = bundles.update(slug, request);
        audit.record(actor(principal), "bundle.update", "bundle", updated.slug(), null);
        return updated;
    }

    @DeleteMapping("/api/admin/bundles/{slug}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteBundle(@PathVariable String slug, Principal principal) {
        bundles.delete(slug);
        audit.record(actor(principal), "bundle.delete", "bundle", slug, null);
    }

    private String actor(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
