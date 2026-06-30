package es.ubu.batchdownloader.catalog;

import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppSearchResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.common.ApiError;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final CatalogRepository catalog;
    private final String scraperApiUrl;
    private final HttpClient httpClient;

    public CatalogController(CatalogRepository catalog, @Value("${app.scraper-api-url}") String scraperApiUrl) {
        this.catalog = catalog;
        this.scraperApiUrl = scraperApiUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @GetMapping("/apps")
    public AppSearchResponse apps(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "os") String operatingSystem,
            @RequestParam(required = false) String architecture,
            @RequestParam(required = false) String tags,
            @RequestParam(defaultValue = "all") String tagMode,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<String> tagList = tags == null || tags.isBlank()
                ? List.of()
                : Arrays.stream(tags.split(",")).map(String::trim).filter(value -> !value.isBlank()).toList();
        return new AppSearchResponse(
                catalog.search(query, status, operatingSystem, architecture, tagList, tagMode, sort, safePage, safePageSize),
                safePage,
                safePageSize,
                catalog.count(query, status, operatingSystem, architecture, tagList, tagMode));
    }

    @GetMapping("/apps/stats")
    public CatalogStatsResponse stats() {
        return catalog.stats();
    }

    @GetMapping("/apps/{appId}")
    public AppDetails details(@PathVariable String appId) {
        return catalog.details(appId);
    }

    @GetMapping("/apps/{appId}/download")
    public ResponseEntity<?> download(@PathVariable String appId, HttpServletResponse servletResponse) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(scraperApiUrl + "/api/apps/" + encode(appId) + "/download"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 307) {
            String location = response.headers().firstValue("location").orElse(null);
            if (location != null) {
                return ResponseEntity.status(307).header(HttpHeaders.LOCATION, location).build();
            }
        }
        if (response.statusCode() == 404) {
            return ResponseEntity.status(404).body(ApiError.of("app_not_found", "La aplicacion no existe."));
        }
        if (response.statusCode() == 409) {
            return ResponseEntity.status(409).body(ApiError.of("installer_unavailable", "No hay instalador disponible."));
        }
        return ResponseEntity.status(502).body(new ApiError(
                "scraper_download_failed",
                "No se pudo obtener la URL de descarga.",
                Map.of("status", response.statusCode())));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
