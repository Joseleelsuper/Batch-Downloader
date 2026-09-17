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
 * Consulta a Core en lote los nombres y páginas oficiales del trabajo y exige correspondencia
 * exacta de elementos y aplicaciones antes de construir accesos manuales.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.JobItemMetadataLookup
 * @see es.ubu.batchdownloader.downloadworker.application.ManualShortcutWriter
 * @since 0.1.0
 * @version 0.1.0
 * @category Adaptadores y persistencia del worker
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
     * Conecta cliente, JSON y configuración interna y retira barras finales de la URL base de Core.
     *
     * @param client Cliente del servicio remoto, configurado antes de componer el adaptador.
     * @param objectMapper Serializador de contratos JSON entre servicios.
     * @param properties Configuración específica del adaptador: destino, credencial y límites de
     *     acceso.
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
     * Evita una petición para selecciones vacías; en otro caso envía los UUID al endpoint interno
     * autenticado del trabajo y valida una respuesta 200 completa.
     *
     * @param jobId UUID del trabajo al que pertenecen todas las entradas del manifiesto.
     * @param requestedItems Elementos admitidos para los que debe recibirse exactamente un metadato
     *     consistente.
     * @return metadatos inmutables por UUID de elemento.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si falla el
     *     transporte, Core no devuelve 200 o el contenido es inconsistente.
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
     * Serializa únicamente los UUID de elemento para que Core compruebe su pertenencia al trabajo.
     *
     * @param items Elementos cuyos UUID se incluyen en la petición de metadatos.
     * @return JSON de la solicitud de metadatos.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si no puede
     *     serializarse la solicitud.
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
     * Envía la petición interna de metadatos y obtiene el cuerpo textual conservando la
     * interrupción del hilo.
     *
     * @param request Petición interna ya construida con token y timeout.
     * @return respuesta HTTP sin interpretar todavía su estado.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si falla la
     *     E/S o se interrumpe la petición.
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
     * Interpreta el JSON como lista de metadatos antes de comprobar su correspondencia con la
     * solicitud.
     *
     * @param body Cuerpo JSON recibido del servicio interno; se valida antes de utilizarlo.
     * @return lista deserializada del servicio Core.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si el
     *     cuerpo no puede interpretarse como el contrato esperado.
     */
    private List<MetadataItem> deserialize(String body) {
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (IOException exception) {
            throw new InfrastructureException("invalid_job_metadata_response", exception);
        }
    }

    /**
     * Exige UUID únicos, nombres no blancos, aplicaciones coincidentes y exactamente el conjunto
     * solicitado, rechazando tanto omisiones como elementos adicionales.
     *
     * @param requestedItems Elementos admitidos para los que debe recibirse exactamente un metadato
     *     consistente.
     * @param response Lista de metadatos recibida de Core que debe corresponder exactamente a la
     *     selección.
     * @return copia inmutable de los metadatos consistentes.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si falta un
     *     campo, hay duplicados o no coincide la selección.
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
     * Construye un fallo de contrato interno sin copiar la respuesta remota.
     *
     * @return error invalid_job_metadata_response con causa de inconsistencia.
     */
    private InfrastructureException invalidResponse() {
        return new InfrastructureException(
                "invalid_job_metadata_response",
                new IllegalArgumentException("Core returned inconsistent job item metadata"));
    }

    /**
     * Limita la consulta de metadatos a los identificadores de elemento que Core debe comprobar
     * dentro del trabajo.
     *
     * @param itemIds UUID de los elementos del trabajo cuyos metadatos públicos se solicitan.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Adaptadores y persistencia del worker
     */
    private record MetadataRequest(List<UUID> itemIds) {}

    /**
     * Recibe la identidad y el nombre público de un elemento junto a su página oficial opcional.
     *
     * @param itemId UUID del elemento admitido dentro del trabajo de descarga.
     * @param appId UUID de la aplicación seleccionada en el catálogo.
     * @param appName Nombre de la aplicación que se muestra en el manifiesto o las instrucciones
     *     manuales.
     * @param officialPageUrl Página oficial utilizada como alternativa manual cuando no puede
     *     entregarse un instalador.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Adaptadores y persistencia del worker
     */
    private record MetadataItem(
            UUID itemId,
            UUID appId,
            String appName,
            String officialPageUrl) {}
}
