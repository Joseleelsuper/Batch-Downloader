package es.ubu.batchdownloader.downloadworker.infrastructure.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.CoreApiProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.DownloadItemMetadata;
import es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HttpJobItemMetadataLookup implements JobItemMetadataLookup {
    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final CoreApiProperties properties;
    private final String baseUrl;

    public HttpJobItemMetadataLookup(
            HttpClient client,
            ObjectMapper objectMapper,
            CoreApiProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    @Override
    public Map<UUID, DownloadItemMetadata> find(
            UUID jobId,
            List<DownloadItemRequest> requestedItems) {
        if (requestedItems.isEmpty()) {
            return Map.of();
        }
        URI endpoint = URI.create(
                baseUrl + "/internal/v1/download-jobs/" + jobId + "/item-metadata");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.timeout())
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("X-Internal-Service-Token", properties.serviceToken())
                .POST(HttpRequest.BodyPublishers.ofString(serialize(requestedItems)))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() >= 500) {
            throw new InfrastructureException(
                    "job_metadata_unavailable",
                    new IllegalStateException("HTTP " + response.statusCode()));
        }
        if (response.statusCode() != 200) {
            throw new InfrastructureException(
                    "job_metadata_http_" + response.statusCode(),
                    new IllegalStateException("Unexpected metadata response"));
        }
        return validate(requestedItems, deserialize(response.body()));
    }

    private String serialize(List<DownloadItemRequest> items) {
        try {
            return objectMapper.writeValueAsString(
                    new MetadataRequest(items.stream().map(DownloadItemRequest::itemId).toList()));
        } catch (IOException exception) {
            throw new InfrastructureException("job_metadata_request_failed", exception);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("job_metadata_interrupted", exception);
        } catch (IOException exception) {
            throw new InfrastructureException("job_metadata_unavailable", exception);
        }
    }

    private List<MetadataItem> deserialize(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (IOException exception) {
            throw new InfrastructureException("invalid_job_metadata_response", exception);
        }
    }

    private Map<UUID, DownloadItemMetadata> validate(
            List<DownloadItemRequest> requestedItems,
            List<MetadataItem> response) {
        if (response == null) {
            throw invalidResponse();
        }
        Map<UUID, DownloadItemRequest> requestedById = new HashMap<>();
        for (DownloadItemRequest item : requestedItems) {
            if (requestedById.put(item.itemId(), item) != null) {
                throw invalidResponse();
            }
        }
        Map<UUID, DownloadItemMetadata> result = new LinkedHashMap<>();
        for (MetadataItem item : response) {
            if (item == null
                    || item.itemId() == null
                    || item.appId() == null
                    || item.appName() == null
                    || item.appName().isBlank()
                    || result.containsKey(item.itemId())) {
                throw invalidResponse();
            }
            DownloadItemRequest requested = requestedById.get(item.itemId());
            if (requested == null || !requested.appId().equals(item.appId())) {
                throw invalidResponse();
            }
            result.put(item.itemId(), new DownloadItemMetadata(
                    item.itemId(),
                    item.appId(),
                    item.appName(),
                    item.officialPageUrl()));
        }
        if (!result.keySet().equals(requestedById.keySet())) {
            throw invalidResponse();
        }
        return Map.copyOf(result);
    }

    private InfrastructureException invalidResponse() {
        return new InfrastructureException(
                "invalid_job_metadata_response",
                new IllegalArgumentException("Core returned inconsistent job item metadata"));
    }

    private record MetadataRequest(List<UUID> itemIds) {}

    private record MetadataItem(
            UUID itemId,
            UUID appId,
            String appName,
            String officialPageUrl) {}
}
