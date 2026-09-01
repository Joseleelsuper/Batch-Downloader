package es.ubu.batchdownloader.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.common.http.InternalHttpExecutor;
import es.ubu.batchdownloader.common.http.InternalHttpRequest;
import es.ubu.batchdownloader.common.http.InternalHttpResponse;
import es.ubu.batchdownloader.common.http.InternalHttpTransportException;
import es.ubu.batchdownloader.common.http.JdkInternalHttpExecutor;
import es.ubu.batchdownloader.common.http.MeteredInternalHttpExecutor;
import es.ubu.batchdownloader.common.http.ServiceTokenInternalHttpExecutor;
import es.ubu.batchdownloader.common.http.TimeoutInternalHttpExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Encapsula la comunicación externa realizada por {@code SemanticSearchClient}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class SemanticSearchClient {
    /**
     * Constante que define {@code FUNCTIONAL_CANDIDATE_LIMIT}.
     */
    private static final int FUNCTIONAL_CANDIDATE_LIMIT = 20000;

    /**
     * Ejecutor HTTP interno con políticas transversales compuestas.
     */
    private final InternalHttpExecutor executor;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code SemanticSearchClient}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code serviceUrl} mantenido por {@code SemanticSearchClient}.
     */
    private final String serviceUrl;
    /**
     * Inicializa una instancia de {@code SemanticSearchClient}.
     *
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param serviceUrl Dirección de {@code service} que debe procesarse.
     * @param internalServiceToken Valor de {@code internalServiceToken} utilizado por la operación.
     * @param requestTimeout Valor de {@code requestTimeout} utilizado por la operación.
     * @param registry Registro opcional para observar las llamadas internas.
     */
    @Autowired
    public SemanticSearchClient(
            ObjectMapper objectMapper,
            @Value("${app.semantic-service-url}") String serviceUrl,
            @Value("${app.semantic-internal-service-token}") String internalServiceToken,
            @Value("${app.semantic-request-timeout}") Duration requestTimeout,
            @Nullable MeterRegistry registry) {
        this(
                objectMapper,
                serviceUrl,
                instrumentedExecutor(internalServiceToken, requestTimeout, registry));
    }

    /**
     * Inicializa una instancia de {@code SemanticSearchClient}.
     *
     * @param httpClient Valor de {@code httpClient} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param serviceUrl Dirección de {@code service} que debe procesarse.
     * @param internalServiceToken Valor de {@code internalServiceToken} utilizado por la operación.
     * @param requestTimeout Valor de {@code requestTimeout} utilizado por la operación.
     */
    SemanticSearchClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String serviceUrl,
            String internalServiceToken,
            Duration requestTimeout) {
        this(
                objectMapper,
                serviceUrl,
                executor(httpClient, internalServiceToken, requestTimeout));
    }

    private SemanticSearchClient(
            ObjectMapper objectMapper,
            String serviceUrl,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.replaceAll("/+$", "");
    }

    /**
     * Resuelve el recurso solicitado mediante {@code resolve}.
     *
     * @param requestedMode Valor de {@code requestedMode} utilizado por la operación.
     * @param query Valor de {@code query} utilizado por la operación.
     * @return Resultado producido por {@code resolve}.
     */
    public SemanticCandidateSet resolve(CatalogSearchMode requestedMode, String query) {
        if (requestedMode == CatalogSearchMode.LEXICAL) {
            return SemanticCandidateSet.lexical();
        }
        if (query == null || query.isBlank()) {
            return SemanticCandidateSet.lexical(CatalogSearchMode.SEMANTIC, null);
        }
        try {
            InternalHttpResponse response = executor.execute(request(query));
            if (response.statusCode() == 401) {
                return fallback("semantic_unauthorized");
            }
            if (response.statusCode() >= 500) {
                return fallback("semantic_index_unavailable");
            }
            if (response.statusCode() >= 400) {
                return fallback(rejectionReason(response.body()));
            }
            SemanticResponse semantic = objectMapper.readValue(
                    response.body(),
                    SemanticResponse.class);
            if (semantic.truncated() || semantic.candidates().size() > FUNCTIONAL_CANDIDATE_LIMIT) {
                return fallback("semantic_candidates_truncated");
            }
            if (semantic.modelVersion() == null || semantic.indexVersion() == null) {
                return fallback("semantic_index_incomplete");
            }
            return new SemanticCandidateSet(
                    CatalogSearchMode.SEMANTIC,
                    CatalogSearchMode.SEMANTIC,
                    objectMapper.writeValueAsString(semantic.candidates()),
                    semantic.modelVersion(),
                    semantic.indexVersion(),
                    null);
        } catch (InternalHttpTransportException exception) {
            return fallback(exception.interrupted()
                    ? "semantic_request_interrupted"
                    : "semantic_service_unavailable");
        } catch (IOException | RuntimeException exception) {
            return fallback("semantic_service_unavailable");
        }
    }

    /**
     * Ejecuta la operación {@code request}.
     *
     * @param query Valor de {@code query} utilizado por la operación.
     * @return Resultado producido por {@code request}.
     * @throws JsonProcessingException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private InternalHttpRequest request(String query) throws JsonProcessingException {
        String body = objectMapper.writeValueAsString(
                new SemanticRequest(query.trim(), FUNCTIONAL_CANDIDATE_LIMIT));
        return new InternalHttpRequest(
                        "semantic",
                        "search",
                        "POST",
                        URI.create(serviceUrl + "/internal/v1/semantic/search"),
                        body)
                .withHeader("Content-Type", "application/json");
    }

    private static InternalHttpExecutor executor(
            HttpClient client,
            String token,
            Duration timeout) {
        InternalHttpExecutor result = new JdkInternalHttpExecutor(client);
        result = new ServiceTokenInternalHttpExecutor(
                result, token == null ? "" : token);
        return new TimeoutInternalHttpExecutor(result, timeout);
    }

    private static InternalHttpExecutor instrumentedExecutor(
            String token,
            Duration timeout,
            MeterRegistry registry) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        InternalHttpExecutor result = executor(client, token, timeout);
        return registry == null ? result : new MeteredInternalHttpExecutor(result, registry);
    }

    /**
     * Ejecuta la operación {@code fallback}.
     *
     * @param reason Valor de {@code reason} utilizado por la operación.
     * @return Resultado producido por {@code fallback}.
     */
    private SemanticCandidateSet fallback(String reason) {
        return SemanticCandidateSet.lexical(CatalogSearchMode.SEMANTIC, reason);
    }

    /**
     * Clasifica un rechazo HTTP del servicio semántico sin depender del texto localizado del error.
     *
     * @param body Cuerpo JSON devuelto por el servicio interno.
     * @return Motivo público de degradación compatible con el catálogo.
     */
    private String rejectionReason(String body) {
        if (body == null || body.isBlank()) {
            return "semantic_request_rejected";
        }
        try {
            JsonNode detail = objectMapper.readTree(body).path("detail");
            if (detail.isObject()
                    && "semantic_query_too_short".equals(detail.path("code").asText())) {
                return "semantic_query_too_short";
            }
            if (detail.isArray()) {
                for (JsonNode error : detail) {
                    if (isShortQueryValidation(error)) {
                        return "semantic_query_too_short";
                    }
                }
            }
        } catch (JsonProcessingException exception) {
            // Un cuerpo no JSON sigue siendo un rechazo genérico, sin revelar su contenido.
        }
        return "semantic_request_rejected";
    }

    /**
     * Reconoce el formato de validación de FastAPI/Pydantic para el campo {@code body.query}.
     *
     * @param error Error individual dentro de {@code detail}.
     * @return {@code true} cuando el error identifica una consulta semántica demasiado corta.
     */
    private static boolean isShortQueryValidation(JsonNode error) {
        JsonNode location = error.path("loc");
        return "string_too_short".equals(error.path("type").asText())
                && location.isArray()
                && location.size() == 2
                && "body".equals(location.get(0).asText())
                && "query".equals(location.get(1).asText());
    }

    /**
     * Representa los datos inmutables de {@code SemanticRequest}.
     *
     * @param query Valor de {@code query} incluido en el record.
     * @param limit Valor de {@code limit} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record SemanticRequest(String query, int limit) {}

    /**
     * Representa los datos inmutables de {@code SemanticResponse}.
     *
     * @param candidates Valor de {@code candidates} incluido en el record.
     * @param modelVersion Valor de {@code modelVersion} incluido en el record.
     * @param indexVersion Valor de {@code indexVersion} incluido en el record.
     * @param truncated Valor de {@code truncated} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record SemanticResponse(
            List<SemanticCandidate> candidates,
            String modelVersion,
            String indexVersion,
            boolean truncated) {
        /**
         * Inicializa una instancia de {@code SemanticResponse}.
         *
         * @param candidates Valor de {@code candidates} utilizado por la operación.
         * @param modelVersion Valor de {@code modelVersion} utilizado por la operación.
         * @param indexVersion Valor de {@code indexVersion} utilizado por la operación.
         * @param truncated Valor de {@code truncated} utilizado por la operación.
         */
        private SemanticResponse {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * Representa los datos inmutables de {@code SemanticCandidate}.
     *
     * @param appId Valor de {@code appId} incluido en el record.
     * @param rank Valor de {@code rank} incluido en el record.
     * @param similarity Valor de {@code similarity} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record SemanticCandidate(String appId, int rank, double similarity) {}
}
