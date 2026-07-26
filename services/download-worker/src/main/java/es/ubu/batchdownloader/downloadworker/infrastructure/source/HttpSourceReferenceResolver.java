package es.ubu.batchdownloader.downloadworker.infrastructure.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.SourceResolverProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

public class HttpSourceReferenceResolver implements SourceReferenceResolver {
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final SourceResolverProperties properties;
    private final String baseUrl;

    public HttpSourceReferenceResolver(
            HttpClient client,
            ObjectMapper objectMapper,
            SourceResolverProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    @Override
    public ResolvedDownloadItem resolve(DownloadItemRequest item) {
        URI endpoint = URI.create(baseUrl + "/internal/v1/sources/" + item.sourceRef() + "/resolution");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.timeout())
                .header("Accept", "application/json")
                .header("X-Internal-Service-Token", properties.serviceToken())
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 404) {
            throw new DownloadRejectedException("source_not_found");
        }
        if (response.statusCode() == 409) {
            throw new DownloadRejectedException("source_not_verified");
        }
        if (response.statusCode() >= 500) {
            // Resolution is scoped to one installer. Treat an upstream 5xx as
            // an item failure so the remaining bundle can still be produced.
            // Re-throwing it as infrastructure used to retry the whole event
            // and left the Core job active after Rabbit exhausted its retries.
            throw new DownloadRejectedException(
                    "source_resolver_unavailable",
                    new IllegalStateException("HTTP " + response.statusCode()));
        }
        if (response.statusCode() != 200) {
            throw new DownloadRejectedException("source_resolution_http_" + response.statusCode());
        }
        SourceResolutionResponse resolved = deserialize(response.body());
        validateResponse(item, resolved);
        return new ResolvedDownloadItem(
                item.itemId(),
                item.appId(),
                item.sourceRef(),
                URI.create(resolved.url()),
                resolved.expectedFilename(),
                resolved.operatingSystem(),
                resolved.architecture(),
                resolved.expectedSizeBytes(),
                normalizeSha256(resolved.expectedSha256()),
                resolved.expectedMime());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("source_resolution_interrupted", exception);
        } catch (IOException exception) {
            throw new InfrastructureException("source_resolver_unavailable", exception);
        }
    }

    private SourceResolutionResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, SourceResolutionResponse.class);
        } catch (IOException exception) {
            throw new InfrastructureException("invalid_source_resolution_response", exception);
        }
    }

    private void validateResponse(DownloadItemRequest requested, SourceResolutionResponse resolved) {
        if (resolved.sourceRef() == null
                || resolved.appId() == null
                || !resolved.sourceRef().equals(requested.sourceRef())
                || !resolved.appId().equals(requested.appId())
                || !"VERIFIED".equals(resolved.trustStatus())
                || resolved.url() == null
                || resolved.url().isBlank()
                || resolved.operatingSystem() == null
                || resolved.operatingSystem().isBlank()
                || resolved.architecture() == null
                || resolved.architecture().isBlank()) {
            throw new InfrastructureException(
                    "invalid_source_resolution_response",
                    new IllegalArgumentException("Source resolver returned inconsistent identifiers or trust"));
        }
        if (resolved.expectedSizeBytes() != null && resolved.expectedSizeBytes() < 0) {
            throw new InfrastructureException(
                    "invalid_source_resolution_response",
                    new IllegalArgumentException("Negative expectedSizeBytes"));
        }
        normalizeSha256(resolved.expectedSha256());
    }

    private String normalizeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        String normalized = sha256.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new InfrastructureException(
                    "invalid_source_resolution_response",
                    new IllegalArgumentException("Invalid expectedSha256"));
        }
        return normalized;
    }

    public record SourceResolutionResponse(
            UUID sourceRef,
            UUID appId,
            String url,
            String expectedFilename,
            Long expectedSizeBytes,
            String expectedSha256,
            String expectedMime,
            String operatingSystem,
            String architecture,
            String trustStatus) {
    }
}
