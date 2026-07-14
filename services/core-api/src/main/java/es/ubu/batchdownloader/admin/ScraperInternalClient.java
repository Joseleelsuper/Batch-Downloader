package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.common.ConflictException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
        send("/api/internal/scraper/run-once", "", Void.class, "scraper_run_once_failed");
    }

    public DescriptionGeneration generateDescription(String appId) {
        return send(
                "/api/internal/descriptions/generate",
                write(new GenerateDescriptionRequest(appId)),
                DescriptionGeneration.class,
                "description_generation_failed");
    }

    public ContentEnqueueResult enqueueMissingDescriptions() {
        return send(
                "/internal/v1/content/descriptions/enqueue-missing",
                "",
                ContentEnqueueResult.class,
                "description_enqueue_failed");
    }

    private <T> T send(String path, String body, Class<T> responseType, String failureCode) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(scraperApiUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("X-Internal-Service-Token", internalServiceToken)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (!body.isEmpty()) {
            builder.header("Content-Type", "application/json");
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw failure(failureCode);
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
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw failure("scraper_request_serialization_failed");
        }
    }

    private ConflictException failure(String code) {
        return new ConflictException(code, "No se pudo completar la operacion interna del scraper.");
    }

    public record ContentEnqueueResult(int matched, int enqueued, int alreadyActive) {}

    public record DescriptionGeneration(String jobId, String status) {}

    private record GenerateDescriptionRequest(String appId) {}
}
