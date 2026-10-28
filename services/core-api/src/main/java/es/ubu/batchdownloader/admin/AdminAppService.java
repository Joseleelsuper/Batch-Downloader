package es.ubu.batchdownloader.admin;

import es.ubu.batchdownloader.admin.AdminCatalogDtos.PatchAppRequest;
import es.ubu.batchdownloader.admin.AdminCatalogDtos.PatchSourceRequest;
import es.ubu.batchdownloader.admin.AdminCatalogDtos.ReplaceTagsRequest;
import es.ubu.batchdownloader.admin.AdminCatalogDtos.UpsertAppRequest;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerApplyRequest;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerApplyResponse;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerApplyResult;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerInspection;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerInspectionRequest;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscovery;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryApplyRequest;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryApplyResponse;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryApplyResult;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryRequest;
import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerification;
import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerificationRequest;
import es.ubu.batchdownloader.admin.InstallerAbsenceDtos.InstallerAbsenceVerificationSummary;
import es.ubu.batchdownloader.admin.AdminAppRepository.AppCsvExport;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppSearchResponse;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.catalog.CatalogQuery;
import es.ubu.batchdownloader.catalog.SemanticCandidateSet;
import es.ubu.batchdownloader.common.ConflictException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Coordina edición, inspección y auditoría sin depender del transporte HTTP ni de la sesión. */
@Service
public class AdminAppService {
    private final CatalogRepository catalog;
    private final AdminAppRepository adminApps;
    private final AdminAuditService audit;
    private final ScraperInternalClient scraperClient;

    public AdminAppService(CatalogRepository catalog, AdminAppRepository adminApps,
            AdminAuditService audit, ScraperInternalClient scraperClient) {
        this.catalog = catalog;
        this.adminApps = adminApps;
        this.audit = audit;
        this.scraperClient = scraperClient;
    }

    public AppSearchResponse listApps(String query, String status, String operatingSystem, String architecture, String sort, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<String> operatingSystems = operatingSystem == null || operatingSystem.isBlank()
                ? List.of()
                : List.of(operatingSystem);
        SemanticCandidateSet lexicalCandidates = SemanticCandidateSet.lexical();
        CatalogQuery filters = new CatalogQuery(
                query, status, operatingSystems, architecture, List.of(), List.of());
        return new AppSearchResponse(
                catalog.search(filters, sort, safePage, safePageSize, lexicalCandidates),
                safePage,
                safePageSize,
                catalog.count(filters, lexicalCandidates));
    }

    public InstallerAbsenceVerificationSummary absenceVerificationSummary() {
        return adminApps.absenceVerificationSummary();
    }

    public InstallerAbsenceVerification activeAbsenceVerification(String appId) {
        return adminApps.activeAbsenceVerification(appId);
    }

    public InstallerAbsenceVerification confirmInstallerAbsence(String appId, InstallerAbsenceVerificationRequest request, String actor) {
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

    public AppCsvExport exportCsv(String actor) {
        AppCsvExport export = adminApps.exportCsv();
        audit.record(
                actor,
                "app.export_csv",
                "app",
                null,
                Map.of("rows", export.rowCount()));
        return export;
    }

    public AppDetails createApp(UpsertAppRequest request, String actor) {
        AppDetails created = adminApps.create(request);
        audit.record(actor, "app.create", "app", created.id(), null);
        return created;
    }

    public AppDetails patchApp(String appId, PatchAppRequest request, String actor) {
        AppDetails updated = adminApps.patch(appId, request);
        audit.record(actor, "app.update", "app", updated.id(), null);
        return updated;
    }

    public void deleteApp(String appId, String actor) {
        adminApps.delete(appId);
        audit.record(actor, "app.delete", "app", appId, null);
    }

    public Map<String, Object> deleteAllApps(String confirm, String actor) {
        if (!"DELETE_ALL".equals(confirm)) {
            throw new ConflictException(
                    "delete_all_confirmation_required",
                    "Debes confirmar el borrado completo con confirm=DELETE_ALL.");
        }
        int deleted = adminApps.deleteAll();
        audit.record(actor, "app.delete_all", "app", null, Map.of("deleted", deleted));
        return Map.of("deleted", deleted);
    }

    public void replaceTags(String appId, ReplaceTagsRequest request, String actor) {
        adminApps.replaceTags(appId, request.tags());
        audit.record(actor, "app.tags.replace", "app", appId, null);
    }

    public void patchSource(String appId, String sourceId, PatchSourceRequest request, String actor) {
        adminApps.patchSource(appId, sourceId, request);
        audit.record(actor, "app.source.update", "source", sourceId, Map.of("appId", appId));
    }

    public ScraperInternalClient.DescriptionGeneration generateDescription(String appId, String actor) {
        ScraperInternalClient.DescriptionGeneration payload = scraperClient.generateDescription(appId);
        audit.record(
                actor,
                "app.description.generate",
                "app",
                appId,
                Map.of(
                        "jobId", payload.jobId(),
                        "status", payload.status()));
        return payload;
    }

    public ManualInstallerInspection createManualInstallerInspection(String appId, ManualInstallerInspectionRequest request, String actor) {
        ManualInstallerInspection inspection =
                scraperClient.createManualInstallerInspection(appId, request);
        audit.record(
                actor,
                "app.manual_installer.inspect",
                "app",
                appId,
                Map.of(
                        "inspectionId", inspection.id(),
                        "status", inspection.status()));
        return inspection;
    }

    public ManualInstallerInspection currentManualInstallerInspection(String appId) {
        return scraperClient.currentManualInstallerInspection(appId);
    }

    public ManualInstallerInspection manualInstallerInspection(String appId, String inspectionId) {
        return scraperClient.manualInstallerInspection(appId, inspectionId);
    }

    public ManualInstallerApplyResponse applyManualInstallerInspection(String appId, String inspectionId, ManualInstallerApplyRequest request, String actor) {
        ManualInstallerApplyResult result =
                scraperClient.applyManualInstallerInspection(appId, inspectionId, request);
        AppDetails application = catalog.details(result.appId());
        audit.record(
                actor,
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

    public WebsiteAppDiscovery createWebsiteAppDiscovery(WebsiteAppDiscoveryRequest request, String actor) {
        WebsiteAppDiscovery discovery =
                scraperClient.createWebsiteAppDiscovery(request);
        audit.record(
                actor,
                "app.website_discovery.inspect",
                "website_app_discovery",
                discovery.id(),
                Map.of("status", discovery.status()));
        return discovery;
    }

    public WebsiteAppDiscovery websiteAppDiscovery(String discoveryId) {
        return scraperClient.websiteAppDiscovery(discoveryId);
    }

    public WebsiteAppDiscoveryApplyResponse applyWebsiteAppDiscovery(String discoveryId, WebsiteAppDiscoveryApplyRequest request, String actor) {
        WebsiteAppDiscoveryApplyResult result =
                scraperClient.applyWebsiteAppDiscovery(discoveryId, request);
        AppDetails application = catalog.details(result.appId());
        audit.record(
                actor,
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
}
