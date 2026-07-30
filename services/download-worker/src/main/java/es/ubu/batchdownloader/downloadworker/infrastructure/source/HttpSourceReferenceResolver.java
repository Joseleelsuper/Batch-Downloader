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

/**
 * Resuelve los recursos gestionados por {@code HttpSourceReferenceResolver}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
public class HttpSourceReferenceResolver implements SourceReferenceResolver {
    /**
     * Estado {@code client} mantenido por {@code HttpSourceReferenceResolver}.
     */
    private final HttpClient client;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code HttpSourceReferenceResolver}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code properties} mantenido por {@code HttpSourceReferenceResolver}.
     */
    private final SourceResolverProperties properties;
    /**
     * Estado {@code baseUrl} mantenido por {@code HttpSourceReferenceResolver}.
     */
    private final String baseUrl;

    /**
     * Inicializa una instancia de {@code HttpSourceReferenceResolver}.
     *
     * @param client Valor de {@code client} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public HttpSourceReferenceResolver(
            HttpClient client,
            ObjectMapper objectMapper,
            SourceResolverProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    /**
     * Resuelve el recurso solicitado mediante {@code resolve}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     * @return Resultado producido por {@code resolve}.
     * @throws DownloadRejectedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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
            // La resolución se limita a un instalador. Trata un 5xx remoto como fallo
            // del elemento para poder producir el resto del lote. Relanzarlo como fallo
            // de infraestructura reintentaba antes todo el evento y dejaba activo el
            // trabajo de Core cuando Rabbit agotaba sus reintentos.
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
            throw new InfrastructureException("source_resolution_interrupted", exception);
        } catch (IOException exception) {
            throw new InfrastructureException("source_resolver_unavailable", exception);
        }
    }

    /**
     * Ejecuta la operación {@code deserialize}.
     *
     * @param body Cuerpo recibido por la solicitud.
     * @return Resultado producido por {@code deserialize}.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private SourceResolutionResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, SourceResolutionResponse.class);
        } catch (IOException exception) {
            throw new InfrastructureException("invalid_source_resolution_response", exception);
        }
    }

    /**
     * Valida los datos recibidos mediante {@code validateResponse}.
     *
     * @param requested Valor de {@code requested} utilizado por la operación.
     * @param resolved Valor de {@code resolved} utilizado por la operación.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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

    /**
     * Normaliza el valor recibido mediante {@code normalizeSha256}.
     *
     * @param sha256 Valor de {@code sha256} utilizado por la operación.
     * @return Resultado producido por {@code normalizeSha256}.
     * @throws InfrastructureException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
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

    /**
     * Representa los datos inmutables de {@code SourceResolutionResponse}.
     *
     * @param sourceRef Valor de {@code sourceRef} incluido en el record.
     * @param appId Valor de {@code appId} incluido en el record.
     * @param url Valor de {@code url} incluido en el record.
     * @param expectedFilename Valor de {@code expectedFilename} incluido en el record.
     * @param expectedSizeBytes Valor de {@code expectedSizeBytes} incluido en el record.
     * @param expectedSha256 Valor de {@code expectedSha256} incluido en el record.
     * @param expectedMime Valor de {@code expectedMime} incluido en el record.
     * @param operatingSystem Valor de {@code operatingSystem} incluido en el record.
     * @param architecture Valor de {@code architecture} incluido en el record.
     * @param trustStatus Valor de {@code trustStatus} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
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
