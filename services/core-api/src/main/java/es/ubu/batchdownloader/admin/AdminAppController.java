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
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscovery;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryApplyRequest;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryApplyResponse;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryApplyResult;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryRequest;
import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerification;
import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerificationRequest;
import es.ubu.batchdownloader.admin.AdminDtos.InstallerAbsenceVerificationSummary;
import es.ubu.batchdownloader.admin.AdminAppRepository.AppCsvExport;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppSearchResponse;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.catalog.SemanticCandidateSet;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.identity.infrastructure.security.AccountPrincipal;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

/**
 * Expone las operaciones HTTP gestionadas por {@code AdminAppController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
public class AdminAppController {
    /**
     * Estado {@code catalog} mantenido por {@code AdminAppController}.
     */
    private final CatalogRepository catalog;
    /**
     * Estado {@code adminApps} mantenido por {@code AdminAppController}.
     */
    private final AdminAppRepository adminApps;
    /**
     * Estado {@code audit} mantenido por {@code AdminAppController}.
     */
    private final AdminAuditService audit;
    /**
     * Dependencia {@code scraperClient} utilizada por {@code AdminAppController}.
     */
    private final ScraperInternalClient scraperClient;

    /**
     * Inicializa una instancia de {@code AdminAppController}.
     *
     * @param catalog Acceso al catálogo utilizado por la operación.
     * @param adminApps Valor de {@code adminApps} utilizado por la operación.
     * @param audit Valor de {@code audit} utilizado por la operación.
     * @param scraperClient Valor de {@code scraperClient} utilizado por la operación.
     */
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

    /**
     * Enumera los elementos solicitados mediante {@code listApps}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param operatingSystem Valor de {@code operatingSystem} utilizado por la operación.
     * @param architecture Valor de {@code architecture} utilizado por la operación.
     * @param sort Valor de {@code sort} utilizado por la operación.
     * @param page Número de página solicitado.
     * @param pageSize Número máximo de elementos incluidos en una página.
     * @return Resultado producido por {@code listApps}.
     */
    @GetMapping("/api/v1/admin/apps")
    public AppSearchResponse listApps(
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "unresolved") String status,
            @RequestParam(required = false, name = "os") String operatingSystem,
            @RequestParam(required = false) String architecture,
            @RequestParam(defaultValue = "updated") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<String> operatingSystems = operatingSystem == null || operatingSystem.isBlank()
                ? List.of()
                : List.of(operatingSystem);
        SemanticCandidateSet lexicalCandidates = SemanticCandidateSet.lexical();
        return new AppSearchResponse(
                catalog.search(
                        query,
                        status,
                        operatingSystems,
                        architecture,
                        List.of(),
                        List.of(),
                        sort,
                        safePage,
                        safePageSize,
                        lexicalCandidates),
                safePage,
                safePageSize,
                catalog.count(
                        query,
                        status,
                        operatingSystems,
                        architecture,
                        List.of(),
                        List.of(),
                        lexicalCandidates));
    }

    /** Devuelve el criterio de cierre de la campaña de ausencias. */
    @GetMapping("/api/v1/admin/apps/absence-verifications/summary")
    public InstallerAbsenceVerificationSummary absenceVerificationSummary() {
        return adminApps.absenceVerificationSummary();
    }

    /** Obtiene la evidencia activa de una aplicación, si existe. */
    @GetMapping("/api/v1/admin/apps/{appId}/absence-verification")
    public InstallerAbsenceVerification activeAbsenceVerification(
            @PathVariable String appId) {
        return adminApps.activeAbsenceVerification(appId);
    }

    /** Registra una ausencia confirmada y audita al responsable sin guardar binarios. */
    @PostMapping("/api/v1/admin/apps/{appId}/absence-verification")
    @ResponseStatus(HttpStatus.CREATED)
    public InstallerAbsenceVerification confirmInstallerAbsence(
            @PathVariable String appId,
            @Valid @RequestBody InstallerAbsenceVerificationRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        String actor = actor(principal);
        InstallerAbsenceVerification verification =
                adminApps.confirmInstallerAbsence(appId, request, actor);
        audit.record(
                actor,
                "app.installer_absence.confirm",
                "app",
                appId,
                Map.of("reasonCode", request.reasonCode()));
        return verification;
    }

    /**
     * Ejecuta la operación {@code exportCsv}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code exportCsv}.
     */
    @GetMapping(value = "/api/v1/admin/apps/export.csv", produces = "text/csv")
    public ResponseEntity<String> exportCsv(
            @AuthenticationPrincipal AccountPrincipal principal) {
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

    /**
     * Crea el recurso solicitado mediante {@code createApp}.
     *
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code createApp}.
     */
    @PostMapping("/api/v1/admin/apps")
    @ResponseStatus(HttpStatus.CREATED)
    public AppDetails createApp(
            @Valid @RequestBody UpsertAppRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        AppDetails created = adminApps.create(request);
        audit.record(actor(principal), "app.create", "app", created.id(), null);
        return created;
    }

    /**
     * Ejecuta la operación {@code patchApp}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code patchApp}.
     */
    @PatchMapping("/api/v1/admin/apps/{appId}")
    public AppDetails patchApp(
            @PathVariable String appId,
            @RequestBody PatchAppRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        AppDetails updated = adminApps.patch(appId, request);
        audit.record(actor(principal), "app.update", "app", updated.id(), null);
        return updated;
    }

    /**
     * Elimina el recurso solicitado mediante {@code deleteApp}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     */
    @DeleteMapping("/api/v1/admin/apps/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteApp(
            @PathVariable String appId,
            @AuthenticationPrincipal AccountPrincipal principal) {
        adminApps.delete(appId);
        audit.record(actor(principal), "app.delete", "app", appId, null);
    }

    /**
     * Elimina el recurso solicitado mediante {@code deleteAllApps}.
     *
     * @param confirm Valor de {@code confirm} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Mapa con los datos producidos por la operación.
     * @throws ConflictException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    @DeleteMapping("/api/v1/admin/apps")
    public Map<String, Object> deleteAllApps(
            @RequestParam(required = false) String confirm,
            @AuthenticationPrincipal AccountPrincipal principal) {
        if (!"DELETE_ALL".equals(confirm)) {
            throw new ConflictException(
                    "delete_all_confirmation_required",
                    "Debes confirmar el borrado completo con confirm=DELETE_ALL.");
        }
        int deleted = adminApps.deleteAll();
        audit.record(actor(principal), "app.delete_all", "app", null, Map.of("deleted", deleted));
        return Map.of("deleted", deleted);
    }

    /**
     * Ejecuta la operación {@code replaceTags}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     */
    @PutMapping("/api/v1/admin/apps/{appId}/tags")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void replaceTags(
            @PathVariable String appId,
            @RequestBody ReplaceTagsRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        adminApps.replaceTags(appId, request.tags());
        audit.record(actor(principal), "app.tags.replace", "app", appId, null);
    }

    /**
     * Ejecuta la operación {@code patchSource}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param sourceId Identificador de {@code source} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     */
    @PatchMapping("/api/v1/admin/apps/{appId}/sources/{sourceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void patchSource(
            @PathVariable String appId,
            @PathVariable String sourceId,
            @RequestBody PatchSourceRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        adminApps.patchSource(appId, sourceId, request);
        audit.record(actor(principal), "app.source.update", "source", sourceId, Map.of("appId", appId));
    }

    /**
     * Ejecuta la operación {@code generateDescription}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code generateDescription}.
     */
    @PostMapping("/api/v1/admin/apps/{appId}/generate-description")
    public ResponseEntity<ScraperInternalClient.DescriptionGeneration> generateDescription(
            @PathVariable String appId,
            @AuthenticationPrincipal AccountPrincipal principal) {
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

    /**
     * Crea el recurso solicitado mediante {@code createManualInstallerInspection}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code createManualInstallerInspection}.
     */
    @PostMapping("/api/v1/admin/apps/{appId}/manual-installer-inspections")
    public ResponseEntity<ManualInstallerInspection> createManualInstallerInspection(
            @PathVariable String appId,
            @Valid @RequestBody ManualInstallerInspectionRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
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

    /**
     * Ejecuta la operación {@code currentManualInstallerInspection}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Resultado producido por {@code currentManualInstallerInspection}.
     */
    @GetMapping("/api/v1/admin/apps/{appId}/manual-installer-inspections/current")
    public ManualInstallerInspection currentManualInstallerInspection(
            @PathVariable String appId) {
        return scraperClient.currentManualInstallerInspection(appId);
    }

    /**
     * Ejecuta la operación {@code manualInstallerInspection}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param inspectionId Identificador de {@code inspection} utilizado por la operación.
     * @return Resultado producido por {@code manualInstallerInspection}.
     */
    @GetMapping("/api/v1/admin/apps/{appId}/manual-installer-inspections/{inspectionId}")
    public ManualInstallerInspection manualInstallerInspection(
            @PathVariable String appId,
            @PathVariable String inspectionId) {
        return scraperClient.manualInstallerInspection(appId, inspectionId);
    }

    /**
     * Ejecuta la operación {@code applyManualInstallerInspection}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param inspectionId Identificador de {@code inspection} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code applyManualInstallerInspection}.
     */
    @PostMapping(
            "/api/v1/admin/apps/{appId}/manual-installer-inspections/{inspectionId}/apply")
    public ManualInstallerApplyResponse applyManualInstallerInspection(
            @PathVariable String appId,
            @PathVariable String inspectionId,
            @Valid @RequestBody ManualInstallerApplyRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
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
                        "sourceRefs", result.sourceRefs(),
                        "catalogStatus", result.catalogStatus()));
        return new ManualInstallerApplyResponse(
                application,
                result.sourceRef(),
                result.sourceRefs(),
                result.warnings());
    }

    /**
     * Crea el recurso solicitado mediante {@code createWebsiteAppDiscovery}.
     *
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code createWebsiteAppDiscovery}.
     */
    @PostMapping("/api/v1/admin/app-discoveries")
    public ResponseEntity<WebsiteAppDiscovery> createWebsiteAppDiscovery(
            @Valid @RequestBody WebsiteAppDiscoveryRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        WebsiteAppDiscovery discovery =
                scraperClient.createWebsiteAppDiscovery(request);
        audit.record(
                actor(principal),
                "app.website_discovery.inspect",
                "website_app_discovery",
                discovery.id(),
                Map.of("status", discovery.status()));
        return ResponseEntity.accepted().body(discovery);
    }

    /**
     * Ejecuta la operación {@code websiteAppDiscovery}.
     *
     * @param discoveryId Identificador de {@code discovery} utilizado por la operación.
     * @return Resultado producido por {@code websiteAppDiscovery}.
     */
    @GetMapping("/api/v1/admin/app-discoveries/{discoveryId}")
    public WebsiteAppDiscovery websiteAppDiscovery(
            @PathVariable String discoveryId) {
        return scraperClient.websiteAppDiscovery(discoveryId);
    }

    /**
     * Ejecuta la operación {@code applyWebsiteAppDiscovery}.
     *
     * @param discoveryId Identificador de {@code discovery} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code applyWebsiteAppDiscovery}.
     */
    @PostMapping("/api/v1/admin/app-discoveries/{discoveryId}/apply")
    public WebsiteAppDiscoveryApplyResponse applyWebsiteAppDiscovery(
            @PathVariable String discoveryId,
            @Valid @RequestBody WebsiteAppDiscoveryApplyRequest request,
            @AuthenticationPrincipal AccountPrincipal principal) {
        WebsiteAppDiscoveryApplyResult result =
                scraperClient.applyWebsiteAppDiscovery(discoveryId, request);
        AppDetails application = catalog.details(result.appId());
        audit.record(
                actor(principal),
                "app.website_discovery.apply",
                "app",
                result.appId(),
                Map.of(
                        "discoveryId", discoveryId,
                        "catalogStatus", result.catalogStatus(),
                        "installerCount", result.installerCount()));
        return new WebsiteAppDiscoveryApplyResponse(
                application,
                result.installerCount(),
                result.warnings());
    }

    /**
     * Ejecuta la operación {@code actor}.
     *
     * @param principal Identidad autenticada que ejecuta la operación.
     * @return Resultado producido por {@code actor}.
     */
    private String actor(AccountPrincipal principal) {
        return AdminActor.require(principal);
    }
}
