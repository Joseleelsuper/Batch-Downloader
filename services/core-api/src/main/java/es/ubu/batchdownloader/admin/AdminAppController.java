package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminDtos.PatchAppRequest;
import es.ubu.batchdownloader.admin.AdminDtos.PatchSourceRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ReplaceTagsRequest;
import es.ubu.batchdownloader.admin.AdminDtos.UpsertAppRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerApplyRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerApplyResponse;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerApplyResult;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerInspection;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerInspectionRequest;
import es.ubu.batchdownloader.admin.AdminAppRepository.AppCsvExport;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppSearchResponse;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminAppController {
    private final CatalogRepository catalog;
    private final AdminAppRepository adminApps;
    private final AdminAuditService audit;
    private final ScraperInternalClient scraperClient;

    public AdminAppController(
            CatalogRepository catalog,
            AdminAppRepository adminApps,
            AdminAuditService audit,
            ScraperInternalClient scraperClient) {
        this.catalog = catalog;
        this.adminApps = adminApps;
        this.audit = audit;
        this.scraperClient = scraperClient;
    }

    @GetMapping("/api/admin/apps")
    public AppSearchResponse listApps(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "unresolved") String status,
            @RequestParam(required = false, name = "os") String operatingSystem,
            @RequestParam(required = false) String architecture,
            @RequestParam(required = false) String tags,
            @RequestParam(defaultValue = "all") String tagMode,
            @RequestParam(defaultValue = "updated") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<String> tagList = tags == null || tags.isBlank()
                ? List.of()
                : Arrays.stream(tags.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
        return new AppSearchResponse(
                catalog.search(query, status, operatingSystem == null || operatingSystem.isBlank() ? List.of() : List.of(operatingSystem), architecture, tagList, List.of(), null, tagMode, sort, safePage, safePageSize),
                safePage,
                safePageSize,
                catalog.count(query, status, operatingSystem == null || operatingSystem.isBlank() ? List.of() : List.of(operatingSystem), architecture, tagList, List.of(), null, tagMode));
    }

    @GetMapping(value = "/api/admin/apps/export.csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(Principal principal) {
        AppCsvExport export = adminApps.exportCsv();
        audit.record(
                actor(principal),
                "app.export_csv",
                "app",
                null,
                Map.of("rows", export.rowCount()));
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("batch-downloader-apps.csv", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(export.content());
    }

    @PostMapping("/api/admin/apps")
    @ResponseStatus(HttpStatus.CREATED)
    public AppDetails createApp(@Valid @RequestBody UpsertAppRequest request, Principal principal) {
        AppDetails created = adminApps.create(request);
        audit.record(actor(principal), "app.create", "app", created.id(), null);
        return created;
    }

    @PatchMapping("/api/admin/apps/{appId}")
    public AppDetails patchApp(
            @PathVariable String appId,
            @RequestBody PatchAppRequest request,
            Principal principal) {
        AppDetails updated = adminApps.patch(appId, request);
        audit.record(actor(principal), "app.update", "app", updated.id(), null);
        return updated;
    }

    @DeleteMapping("/api/admin/apps/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApp(@PathVariable String appId, Principal principal) {
        adminApps.delete(appId);
        audit.record(actor(principal), "app.delete", "app", appId, null);
    }

    @DeleteMapping("/api/admin/apps")
    public Map<String, Object> deleteAllApps(
            @RequestParam(required = false) String confirm,
            Principal principal) {
        if (!"DELETE_ALL".equals(confirm)) {
            throw new ConflictException(
                    "delete_all_confirmation_required",
                    "Debes confirmar el borrado completo con confirm=DELETE_ALL.");
        }
        int deleted = adminApps.deleteAll();
        audit.record(actor(principal), "app.delete_all", "app", null, Map.of("deleted", deleted));
        return Map.of("deleted", deleted);
    }

    @PutMapping("/api/admin/apps/{appId}/tags")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceTags(
            @PathVariable String appId,
            @RequestBody ReplaceTagsRequest request,
            Principal principal) {
        adminApps.replaceTags(appId, request.tags());
        audit.record(actor(principal), "app.tags.replace", "app", appId, null);
    }

    @PatchMapping("/api/admin/apps/{appId}/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void patchSource(
            @PathVariable String appId,
            @PathVariable String sourceId,
            @RequestBody PatchSourceRequest request,
            Principal principal) {
        adminApps.patchSource(appId, sourceId, request);
        audit.record(actor(principal), "app.source.update", "source", sourceId, Map.of("appId", appId));
    }

    @PostMapping("/api/admin/apps/{appId}/generate-description")
    public ResponseEntity<ScraperInternalClient.DescriptionGeneration> generateDescription(
            @PathVariable String appId,
            Principal principal) {
        ScraperInternalClient.DescriptionGeneration payload = scraperClient.generateDescription(appId);
        audit.record(
                actor(principal),
                "app.description.generate",
                "app",
                appId,
                Map.of(
                        "jobId", payload.jobId(),
                        "status", payload.status()));
        return ResponseEntity.accepted().body(payload);
    }

    @PostMapping("/api/admin/apps/{appId}/manual-installer-inspections")
    public ResponseEntity<ManualInstallerInspection> createManualInstallerInspection(
            @PathVariable String appId,
            @Valid @RequestBody ManualInstallerInspectionRequest request,
            Principal principal) {
        ManualInstallerInspection inspection =
                scraperClient.createManualInstallerInspection(appId, request);
        audit.record(
                actor(principal),
                "app.manual_installer.inspect",
                "app",
                appId,
                Map.of(
                        "inspectionId", inspection.id(),
                        "status", inspection.status()));
        return ResponseEntity.accepted().body(inspection);
    }

    @GetMapping("/api/admin/apps/{appId}/manual-installer-inspections/current")
    public ManualInstallerInspection currentManualInstallerInspection(
            @PathVariable String appId) {
        return scraperClient.currentManualInstallerInspection(appId);
    }

    @GetMapping("/api/admin/apps/{appId}/manual-installer-inspections/{inspectionId}")
    public ManualInstallerInspection manualInstallerInspection(
            @PathVariable String appId,
            @PathVariable String inspectionId) {
        return scraperClient.manualInstallerInspection(appId, inspectionId);
    }

    @PostMapping(
            "/api/admin/apps/{appId}/manual-installer-inspections/{inspectionId}/apply")
    public ManualInstallerApplyResponse applyManualInstallerInspection(
            @PathVariable String appId,
            @PathVariable String inspectionId,
            @Valid @RequestBody ManualInstallerApplyRequest request,
            Principal principal) {
        ManualInstallerApplyResult result =
                scraperClient.applyManualInstallerInspection(appId, inspectionId, request);
        AppDetails application = catalog.details(result.appId());
        audit.record(
                actor(principal),
                "app.manual_installer.apply",
                "app",
                result.appId(),
                Map.of(
                        "inspectionId", inspectionId,
                        "sourceRef", result.sourceRef(),
                        "catalogStatus", result.catalogStatus()));
        return new ManualInstallerApplyResponse(
                application,
                result.sourceRef(),
                result.warnings());
    }

    private String actor(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }
}
