package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.admin.AdminDtos.PatchAppRequest;
import es.ubu.batchdownloader.admin.AdminDtos.PatchSourceRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ReplaceTagsRequest;
import es.ubu.batchdownloader.admin.AdminDtos.UpsertAppRequest;
import es.ubu.batchdownloader.admin.AdminAppRepository.AppCsvExport;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppSearchResponse;
import es.ubu.batchdownloader.catalog.CatalogRepository;
import es.ubu.batchdownloader.common.ConflictException;
import jakarta.validation.Valid;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
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
    private final String scraperApiUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AdminAppController(
            CatalogRepository catalog,
            AdminAppRepository adminApps,
            AdminAuditService audit,
            @Value("${app.scraper-api-url}") String scraperApiUrl,
            ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.adminApps = adminApps;
        this.audit = audit;
        this.scraperApiUrl = scraperApiUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @GetMapping("/api/admin/apps")
    public AppSearchResponse listApps(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
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
                catalog.search(query, status, operatingSystem, architecture, tagList, List.of(), null, tagMode, sort, safePage, safePageSize),
                safePage,
                safePageSize,
                catalog.count(query, status, operatingSystem, architecture, tagList, List.of(), null, tagMode));
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
        adminApps.patchSource(sourceId, request);
        audit.record(actor(principal), "app.source.update", "source", sourceId, Map.of("appId", appId));
    }

    @PostMapping("/api/admin/apps/{appId}/generate-description")
    public Map<String, Object> generateDescription(@PathVariable String appId, Principal principal) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("appId", appId));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(scraperApiUrl + "/api/internal/descriptions/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new ConflictException(
                    "description_generation_failed",
                    "No se pudo generar la descripcion con IA.");
        }
        Map<String, Object> payload = objectMapper.readValue(
                response.body(),
                new TypeReference<Map<String, Object>>() {});
        audit.record(
                actor(principal),
                "app.description.generate",
                "app",
                appId,
                Map.of("provider", payload.get("provider"), "model", payload.get("model")));
        return payload;
    }

    private String actor(Principal principal) {
        return principal == null ? "admin" : principal.getName();
    }

    @SuppressWarnings("unused")
    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
