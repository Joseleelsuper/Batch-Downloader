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

/**
 * Implementa el componente {@code HttpJobItemMetadataLookup}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class HttpJobItemMetadataLookup implements JobItemMetadataLookup {
    /**
     * Estado {@code client} mantenido por {@code HttpJobItemMetadataLookup}.
     */
    private final HttpClient client;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code HttpJobItemMetadataLookup}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code properties} mantenido por {@code HttpJobItemMetadataLookup}.
     */
    private final CoreApiProperties properties;
    /**
     * Estado {@code baseUrl} mantenido por {@code HttpJobItemMetadataLookup}.
     */
    private final String baseUrl;

    /**
     * Inicializa una instancia de {@code HttpJobItemMetadataLookup}.
     *
     * @param client Valor de {@code client} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public HttpJobItemMetadataLookup(
            HttpClient client,
            ObjectMapper objectMapper,
            CoreApiProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    /**
     * Busca el resultado solicitado mediante {@code find}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param requestedItems Valor de {@code requestedItems} utilizado por la operación.
     * @return Mapa con los datos producidos por la operación.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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

    /**
     * Ejecuta la operación {@code serialize}.
     *
     * @param items Colección de elementos que debe procesarse.
     * @return Resultado producido por {@code serialize}.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private String serialize(List<DownloadItemRequest> items) {
        try {
            return objectMapper.writeValueAsString(
                    new MetadataRequest(items.stream().map(DownloadItemRequest::itemId).toList()));
        } catch (IOException exception) {
            throw new InfrastructureException("job_metadata_request_failed", exception);
        }
    }

    /**
     * Envía el contenido solicitado mediante {@code send}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code send}.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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

    /**
     * Ejecuta la operación {@code deserialize}.
     *
     * @param body Cuerpo recibido por la solicitud.
     * @return Colección de elementos obtenidos por la operación.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private List<MetadataItem> deserialize(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (IOException exception) {
            throw new InfrastructureException("invalid_job_metadata_response", exception);
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validate}.
     *
     * @param requestedItems Valor de {@code requestedItems} utilizado por la operación.
     * @param response Respuesta que debe procesarse.
     * @return Mapa con los datos producidos por la operación.
     */
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

    /**
     * Ejecuta la operación {@code invalidResponse}.
     *
     * @return Resultado producido por {@code invalidResponse}.
     */
    private InfrastructureException invalidResponse() {
        return new InfrastructureException(
                "invalid_job_metadata_response",
                new IllegalArgumentException("Core returned inconsistent job item metadata"));
    }

    /**
     * Representa los datos inmutables de {@code MetadataRequest}.
     *
     * @param itemIds Valor de {@code itemIds} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record MetadataRequest(List<UUID> itemIds) {}

    /**
     * Representa los datos inmutables de {@code MetadataItem}.
     *
     * @param itemId Valor de {@code itemId} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param appName Valor de {@code appName} incluido en el record.
     * @param officialPageUrl Valor de {@code officialPageUrl} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record MetadataItem(
            UUID itemId,
            UUID appId,
            String appName,
            String officialPageUrl) {}
}
