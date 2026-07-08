package es.ubu.batchdownloader.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppDetails;
import es.ubu.batchdownloader.catalog.CatalogDtos.AppSearchResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogFacetsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.CatalogStatsResponse;
import es.ubu.batchdownloader.catalog.CatalogDtos.DownloadZipRequest;
import es.ubu.batchdownloader.common.ApiError;
import es.ubu.batchdownloader.common.ConflictException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api")
public class CatalogController {
    private final CatalogRepository catalog;
    private final String scraperApiUrl;
    private final HttpClient httpClient;
    private final HttpClient downloadClient;
    private final ObjectMapper objectMapper;

    public CatalogController(
            CatalogRepository catalog,
            @Value("${app.scraper-api-url}") String scraperApiUrl,
            ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.scraperApiUrl = scraperApiUrl.replaceAll("/+$", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.downloadClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @GetMapping("/apps")
    public AppSearchResponse apps(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "os") String operatingSystem,
            @RequestParam(required = false) String architecture,
            @RequestParam(required = false, name = "tag") List<String> tag,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) List<String> publisher,
            @RequestParam(required = false) Integer tagMatchMin,
            @RequestParam(defaultValue = "all") String tagMode,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));
        List<String> tagList = parseRepeatedAndCsv(tag, tags);
        List<String> publisherList = parseRepeated(publisher);
        return new AppSearchResponse(
                catalog.search(query, status, operatingSystem, architecture, tagList, publisherList, tagMatchMin, tagMode, sort, safePage, safePageSize),
                safePage,
                safePageSize,
                catalog.count(query, status, operatingSystem, architecture, tagList, publisherList, tagMatchMin, tagMode));
    }

    @GetMapping("/apps/stats")
    public CatalogStatsResponse stats() {
        return catalog.stats();
    }

    @GetMapping("/apps/facets")
    public CatalogFacetsResponse facets(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, name = "os") String operatingSystem,
            @RequestParam(required = false) String architecture,
            @RequestParam(required = false, name = "tag") List<String> tag,
            @RequestParam(required = false) String tags,
            @RequestParam(required = false) List<String> publisher,
            @RequestParam(required = false) Integer tagMatchMin,
            @RequestParam(defaultValue = "all") String tagMode) {
        return catalog.facets(
                query,
                status,
                operatingSystem,
                architecture,
                parseRepeatedAndCsv(tag, tags),
                parseRepeated(publisher),
                tagMatchMin,
                tagMode);
    }

    @GetMapping("/apps/{appId}")
    public AppDetails details(@PathVariable String appId) {
        return catalog.details(appId);
    }

    @GetMapping("/apps/{appId}/download")
    public ResponseEntity<?> download(
            @PathVariable String appId,
            @RequestParam(required = false) String optionId,
            HttpServletResponse servletResponse) throws Exception {
        AppDetails app = catalog.details(appId);
        return redirectToInstaller(app.slug(), optionId);
    }

    @PostMapping("/apps/downloads/zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(@RequestBody DownloadZipRequest request) {
        List<String> ids = request == null || request.appIds() == null
                ? List.of()
                : request.appIds().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        if (ids.isEmpty()) {
            throw new ConflictException("no_apps_selected", "Selecciona al menos una aplicacion.");
        }
        if (ids.size() > 100) {
            throw new ConflictException("too_many_apps_selected", "Solo se pueden descargar hasta 100 aplicaciones.");
        }

        List<ZipCandidate> candidates = new ArrayList<>();
        List<Map<String, Object>> manifest = new ArrayList<>();
        for (String id : ids) {
            AppDetails app = catalog.details(id);
            try {
                String location = installerLocation(app.slug());
                candidates.add(new ZipCandidate(app, location));
            } catch (Exception exception) {
                manifest.add(manifestItem(app, null, "skipped", exception.getMessage()));
            }
        }
        if (candidates.isEmpty()) {
            throw new ConflictException("installer_unavailable", "No hay instaladores disponibles para descargar.");
        }

        StreamingResponseBody body = outputStream -> {
            Set<String> usedNames = new LinkedHashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
                for (ZipCandidate candidate : candidates) {
                    String filename = uniqueFilename(filenameFor(candidate.app()), usedNames);
                    try {
                        HttpRequest requestDownload = HttpRequest.newBuilder()
                                .uri(URI.create(candidate.location()))
                                .GET()
                                .build();
                        HttpResponse<InputStream> response = downloadClient.send(
                                requestDownload,
                                HttpResponse.BodyHandlers.ofInputStream());
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            manifest.add(manifestItem(
                                    candidate.app(),
                                    filename,
                                    "failed",
                                    "HTTP " + response.statusCode()));
                            continue;
                        }
                        zip.putNextEntry(new ZipEntry(filename));
                        try (InputStream input = response.body()) {
                            input.transferTo(zip);
                        }
                        zip.closeEntry();
                        manifest.add(manifestItem(candidate.app(), filename, "downloaded", null));
                    } catch (Exception exception) {
                        manifest.add(manifestItem(candidate.app(), filename, "failed", exception.getMessage()));
                    }
                }
                zip.putNextEntry(new ZipEntry("manifest.json"));
                byte[] manifestBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of(
                        "generatedAt", LocalDateTime.now().toString(),
                        "items", manifest));
                zip.write(manifestBytes);
                zip.closeEntry();
            }
        };

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("batch-downloader-apps.zip")
                        .build()
                        .toString())
                .body(body);
    }

    private ResponseEntity<?> redirectToInstaller(String appId, String optionId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(scraperApiUrl + "/api/apps/" + encode(appId) + "/download" + optionQuery(optionId)))
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

    private String installerLocation(String appId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(scraperApiUrl + "/api/apps/" + encode(appId) + "/download"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 307) {
            return response.headers()
                    .firstValue("location")
                    .orElseThrow(() -> new ConflictException(
                            "installer_unavailable",
                            "No hay instalador disponible."));
        }
        if (response.statusCode() == 404) {
            throw new ConflictException("app_not_found", "La aplicacion no existe.");
        }
        if (response.statusCode() == 409) {
            throw new ConflictException("installer_unavailable", "No hay instalador disponible.");
        }
        throw new ConflictException("scraper_download_failed", "No se pudo obtener la URL de descarga.");
    }

    private Map<String, Object> manifestItem(AppDetails app, String filename, String status, String error) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", app.id());
        item.put("slug", app.slug());
        item.put("packageId", app.packageId());
        item.put("name", app.name());
        item.put("filename", filename);
        item.put("status", status);
        if (error != null && !error.isBlank()) {
            item.put("error", error);
        }
        return item;
    }

    private String filenameFor(AppDetails app) {
        if (app.installerFilename() != null && !app.installerFilename().isBlank()) {
            return sanitizeFilename(app.installerFilename());
        }
        String extension = app.installerType() == null || app.installerType().isBlank()
                ? "exe"
                : app.installerType().replace(".", "").toLowerCase();
        return sanitizeFilename(app.name()) + "." + extension;
    }

    private String uniqueFilename(String filename, Set<String> usedNames) {
        String candidate = filename;
        int dot = filename.lastIndexOf('.');
        String base = dot > 0 ? filename.substring(0, dot) : filename;
        String extension = dot > 0 ? filename.substring(dot) : "";
        int suffix = 2;
        while (!usedNames.add(candidate)) {
            candidate = base + "-" + suffix++ + extension;
        }
        return candidate;
    }

    private String sanitizeFilename(String value) {
        String sanitized = value == null ? "installer" : value.trim().replaceAll("[\\\\/:*?\"<>|]+", "-");
        sanitized = sanitized.replaceAll("\\s+", " ").replaceAll("(^\\.|\\.$)", "");
        return sanitized.isBlank() ? "installer" : sanitized;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String optionQuery(String optionId) {
        return optionId == null || optionId.isBlank()
                ? ""
                : "?optionId=" + encode(optionId);
    }

    private List<String> parseRepeatedAndCsv(List<String> repeated, String csv) {
        List<String> values = new ArrayList<>(parseRepeated(repeated));
        if (csv != null && !csv.isBlank()) {
            for (String value : csv.split(",")) {
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        }
        return values.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private List<String> parseRepeated(List<String> repeated) {
        if (repeated == null || repeated.isEmpty()) {
            return List.of();
        }
        return repeated.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private record ZipCandidate(AppDetails app, String location) {}
}
