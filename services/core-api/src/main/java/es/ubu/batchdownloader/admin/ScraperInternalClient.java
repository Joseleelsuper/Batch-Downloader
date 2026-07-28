package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerApplyRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerApplyResult;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerInspection;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerInspectionRequest;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscovery;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryApplyRequest;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryApplyResult;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryRequest;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.ServiceUnavailableException;
import es.ubu.batchdownloader.common.UnprocessableEntityException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Typed boundary for Core-to-scraper administrative calls. */
@Component
public class ScraperInternalClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String scraperApiUrl;
    private final String internalServiceToken;

    public ScraperInternalClient(
            ObjectMapper objectMapper,
            @Value("${app.scraper-api-url}") String scraperApiUrl,
            @Value("${app.scraper-internal-service-token}") String internalServiceToken) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = objectMapper;
        this.scraperApiUrl = scraperApiUrl.replaceAll("/+$", "");
        this.internalServiceToken = internalServiceToken;
    }

    public void triggerRunOnce() {
        post("/api/internal/scraper/run-once", "", Void.class, "scraper_run_once_failed");
    }

    public DescriptionGeneration generateDescription(String appId) {
        return post(
                "/internal/v1/content/descriptions/generate",
                write(new GenerateDescriptionRequest(appId)),
                DescriptionGeneration.class,
                "description_generation_failed");
    }

    public ContentEnqueueResult enqueueMissingDescriptions() {
        return post(
                "/internal/v1/content/descriptions/enqueue-missing",
                "",
                ContentEnqueueResult.class,
                "description_enqueue_failed");
    }

    public ManualInstallerInspection createManualInstallerInspection(
            String appId,
            ManualInstallerInspectionRequest request) {
        return post(
                manualInspectionPath(appId),
                write(request),
                ManualInstallerInspection.class,
                "manual_installer_inspection_failed");
    }

    public ManualInstallerInspection currentManualInstallerInspection(String appId) {
        return get(
                manualInspectionPath(appId) + "/current",
                ManualInstallerInspection.class,
                "manual_installer_inspection_failed");
    }

    public ManualInstallerInspection manualInstallerInspection(
            String appId,
            String inspectionId) {
        return get(
                manualInspectionPath(appId) + "/" + uuidSegment(inspectionId),
                ManualInstallerInspection.class,
                "manual_installer_inspection_failed");
    }

    public ManualInstallerApplyResult applyManualInstallerInspection(
            String appId,
            String inspectionId,
            ManualInstallerApplyRequest request) {
        return post(
                manualInspectionPath(appId) + "/" + uuidSegment(inspectionId) + "/apply",
                write(request),
                ManualInstallerApplyResult.class,
                "manual_installer_apply_failed");
    }

    public WebsiteAppDiscovery createWebsiteAppDiscovery(
            WebsiteAppDiscoveryRequest request) {
        return post(
                websiteDiscoveryPath(),
                write(request),
                WebsiteAppDiscovery.class,
                "website_app_discovery_failed");
    }

    public WebsiteAppDiscovery websiteAppDiscovery(String discoveryId) {
        return get(
                websiteDiscoveryPath() + "/" + uuidSegment(discoveryId),
                WebsiteAppDiscovery.class,
                "website_app_discovery_failed");
    }

    public WebsiteAppDiscoveryApplyResult applyWebsiteAppDiscovery(
            String discoveryId,
            WebsiteAppDiscoveryApplyRequest request) {
        return post(
                websiteDiscoveryPath() + "/" + uuidSegment(discoveryId) + "/apply",
                write(request),
                WebsiteAppDiscoveryApplyResult.class,
                "website_app_discovery_apply_failed");
    }

    private String manualInspectionPath(String appId) {
        return "/internal/v1/admin/apps/" + uuidSegment(appId)
                + "/manual-installer-inspections";
    }

    private String websiteDiscoveryPath() {
        return "/internal/v1/admin/app-discoveries";
    }

    private String uuidSegment(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("invalid_identifier", "El identificador no es válido.");
        }
    }

    private <T> T get(String path, Class<T> responseType, String failureCode) {
        return send("GET", path, "", responseType, failureCode);
    }

    private <T> T post(
            String path,
            String body,
            Class<T> responseType,
            String failureCode) {
        return send("POST", path, body, responseType, failureCode);
    }

    private <T> T send(
            String method,
            String path,
            String body,
            Class<T> responseType,
            String failureCode) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(scraperApiUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-Internal-Service-Token", internalServiceToken);
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        if (!body.isEmpty()) {
            builder.header("Content-Type", "application/json");
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw upstreamFailure(response.statusCode(), response.body(), failureCode);
            }
            if (responseType == Void.class) {
                return null;
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(failureCode);
        } catch (IOException exception) {
            throw failure(failureCode);
        } catch (IllegalArgumentException exception) {
            throw failure(failureCode);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw failure("scraper_request_serialization_failed");
        }
    }

    private RuntimeException upstreamFailure(int status, String body, String fallbackCode) {
        String code = upstreamCode(body, fallbackCode);
        String message = "No se pudo completar la operación interna del scraper.";
        return switch (status) {
            case 400 -> new BadRequestException(code, message);
            case 404 -> new NotFoundException(code, message);
            case 409 -> new ConflictException(code, message);
            case 422 -> new UnprocessableEntityException(code, message);
            case 401, 403 -> new ServiceUnavailableException(
                    "scraper_internal_auth_failed",
                    message);
            default -> new ServiceUnavailableException(code, message);
        };
    }

    private String upstreamCode(String body, String fallbackCode) {
        try {
            JsonNode detail = objectMapper.readTree(body).path("detail");
            JsonNode code = detail.isObject() ? detail.path("code") : null;
            if (code != null
                    && code.isTextual()
                    && code.textValue().matches("[a-z0-9_:-]{1,120}")) {
                return code.textValue();
            }
        } catch (IOException ignored) {
            // The upstream body is untrusted and is never copied into Core errors.
        }
        return fallbackCode;
    }

    private ServiceUnavailableException failure(String code) {
        return new ServiceUnavailableException(
                code,
                "No se pudo completar la operación interna del scraper.");
    }

    public record ContentEnqueueResult(int matched, int enqueued, int alreadyActive) {}

    public record DescriptionGeneration(String jobId, String status) {}

    private record GenerateDescriptionRequest(String appId) {}
}
