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
 * Expone edición del catálogo e inspecciones y descubrimientos persistentes a administradores,
 * registrando sus acciones con metadatos seguros.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminAppRepository
 * @see es.ubu.batchdownloader.admin.ScraperInternalClient
 * @see es.ubu.batchdownloader.admin.AdminAuditService
 * @since 0.1.0
 * @version 0.1.0
 * @category Administración
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
     * Conecta consulta y edición del catálogo con el cliente del scraper y la auditoría
     * administrativa.
     *
     * @param catalog Consultas del catálogo que enriquecen las aplicaciones y mantienen sus filtros
     *     comunes.
     * @param adminApps Escrituras administrativas, evidencias de ausencia y exportación del
     *     catálogo.
     * @param audit Registro de acciones con actor UUID y metadatos seguros sin URLs resueltas.
     * @param scraperClient Cliente interno autenticado para inspección, descubrimiento y generación
     *     de contenido.
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
     * Aplica búsqueda léxica y filtros administrativos, incluido unresolved, compartiendo consulta
     * y total.
     *
     * @param query Texto de búsqueda administrativa, opcional.
     * @param status Filtro de estado del catálogo; unresolved agrupa review y missing.
     * @param operatingSystem Plataforma singular opcional de la consulta administrativa.
     * @param architecture Arquitectura opcional que debe existir entre las fuentes de la
     *     aplicación.
     * @param sort updated, downloads o name, con la misma política de orden del catálogo.
     * @param page Página desde uno; valores menores se acotan a uno.
     * @param pageSize Elementos por página, acotados a 1–100.
     * @return página de aplicaciones con tamaño efectivo y total filtrado.
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
        CatalogQuery filters = new CatalogQuery(
                query, status, operatingSystems, architecture, List.of(), List.of());
        return new AppSearchResponse(
                catalog.search(filters, sort, safePage, safePageSize, lexicalCandidates),
                safePage,
                safePageSize,
                catalog.count(filters, lexicalCandidates));
    }

    /**
     * Consulta los totales de evidencia vigente de ausencia de instaladores.
     *
     * @return resumen administrativo de verificaciones de ausencia.
     */
    @GetMapping("/api/v1/admin/apps/absence-verifications/summary")
    public InstallerAbsenceVerificationSummary absenceVerificationSummary() {
        return adminApps.absenceVerificationSummary();
    }

    /**
     * Recupera la evidencia activa de ausencia asociada a la aplicación.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @return verificación vigente o null cuando no existe.
     */
    @GetMapping("/api/v1/admin/apps/{appId}/absence-verification")
    public InstallerAbsenceVerification activeAbsenceVerification(
            @PathVariable String appId) {
        return adminApps.activeAbsenceVerification(appId);
    }

    /**
     * Registra evidencia de ausencia bajo el actor administrativo y audita únicamente su código de
     * motivo.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return 201 con la verificación guardada.
     */
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
     * Entrega como adjunto UTF-8 la exportación de aplicaciones con referencias de fuente y
     * registra el número de filas exportadas.
     *
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return CSV batch-downloader-apps.csv sin URLs resueltas.
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
     * Crea una aplicación manual y registra el UUID de la nueva entrada en la auditoría.
     *
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return 201 con el detalle público creado.
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
     * Aplica la edición parcial y audita el UUID de la aplicación modificada.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return detalle actualizado del catálogo.
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
     * Solicita el borrado protegido por inactividad del scraper y audita la eliminación; devuelve
     * 204 al completarse.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
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
     * Exige la confirmación textual de borrado total antes de limpiar el catálogo y auditar la
     * cantidad eliminada.
     *
     * @param confirm Debe ser exactamente DELETE_ALL para solicitar el borrado completo del
     *     catálogo.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return mapa con deleted y número de aplicaciones borradas.
     * @throws es.ubu.batchdownloader.common.ConflictException si falta confirmación o el scraper
     *     mantiene trabajo activo que impide borrar.
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
     * Reemplaza las etiquetas de la aplicación y registra la acción; devuelve 204 al completarse.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
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
     * Modifica únicamente una fuente perteneciente a la aplicación y audita ambas identidades;
     * devuelve 204 al completarse.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param sourceId UUID de una fuente que debe pertenecer a la aplicación de la ruta.
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
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
     * Solicita al scraper una tarea persistente de descripción y audita su UUID y estado.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return 202 con la tarea aceptada, sin contenido del proveedor de generación.
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
     * Solicita inspección persistente de los instaladores propuestos y audita el UUID y estado sin
     * registrar sus URLs.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return 202 con la inspección aceptada.
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
     * Consulta la inspección que permite recuperar el flujo administrativo de la aplicación.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @return inspección actual resuelta por el scraper.
     */
    @GetMapping("/api/v1/admin/apps/{appId}/manual-installer-inspections/current")
    public ManualInstallerInspection currentManualInstallerInspection(
            @PathVariable String appId) {
        return scraperClient.currentManualInstallerInspection(appId);
    }

    /**
     * Consulta una inspección concreta dentro de la aplicación indicada.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param inspectionId UUID de la inspección persistida que pertenece a esa aplicación.
     * @return estado, candidatos y diagnóstico seguro de la inspección.
     */
    @GetMapping("/api/v1/admin/apps/{appId}/manual-installer-inspections/{inspectionId}")
    public ManualInstallerInspection manualInstallerInspection(
            @PathVariable String appId,
            @PathVariable String inspectionId) {
        return scraperClient.manualInstallerInspection(appId, inspectionId);
    }

    /**
     * Solicita revalidar y publicar la selección inspeccionada, consulta el detalle resultante y
     * audita las referencias exactas publicadas.
     *
     * @param appId UUID textual o identificador público de la aplicación; las rutas internas
     *     requieren UUID.
     * @param inspectionId UUID de la inspección persistida que pertenece a esa aplicación.
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return aplicación actualizada, referencias de fuente y advertencias de la aplicación.
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
     * Solicita el descubrimiento persistente de una página oficial y registra su UUID sin exponer
     * las URLs internas.
     *
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return 202 con el descubrimiento aceptado.
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
     * Recupera candidatos, progreso y diagnóstico de un descubrimiento persistido.
     *
     * @param discoveryId UUID del descubrimiento persistido de una página oficial.
     * @return vista administrativa del descubrimiento.
     */
    @GetMapping("/api/v1/admin/app-discoveries/{discoveryId}")
    public WebsiteAppDiscovery websiteAppDiscovery(
            @PathVariable String discoveryId) {
        return scraperClient.websiteAppDiscovery(discoveryId);
    }

    /**
     * Solicita publicar la selección descubierta y enriquece la aplicación resultante; audita
     * estado de catálogo y cantidad de instaladores.
     *
     * @param discoveryId UUID del descubrimiento persistido de una página oficial.
     * @param request Cuerpo validado de la operación; las confirmaciones conservan selección y
     *     versión esperadas.
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return detalle de aplicación, instaladores publicados y advertencias.
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
     * Exige el principal administrativo y extrae su UUID estable para auditar la acción.
     *
     * @param principal Principal administrativo ya autorizado por Spring Security; su UUID
     *     identifica el actor auditado.
     * @return UUID textual del actor.
     */
    private String actor(AccountPrincipal principal) {
        return AdminActor.require(principal);
    }
}
