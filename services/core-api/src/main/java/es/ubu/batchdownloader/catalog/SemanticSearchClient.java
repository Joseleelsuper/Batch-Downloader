package es.ubu.batchdownloader.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
     * Dependencia {@code httpClient} utilizada por {@code SemanticSearchClient}.
     */
    private final HttpClient httpClient;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code SemanticSearchClient}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code serviceUrl} mantenido por {@code SemanticSearchClient}.
     */
    private final String serviceUrl;
    /**
     * Estado {@code internalServiceToken} mantenido por {@code SemanticSearchClient}.
     */
    private final String internalServiceToken;
    /**
     * Estado {@code requestTimeout} mantenido por {@code SemanticSearchClient}.
     */
    private final Duration requestTimeout;
    /**
     * Estado {@code enabled} mantenido por {@code SemanticSearchClient}.
     */
    private final boolean enabled;

    /**
     * Inicializa una instancia de {@code SemanticSearchClient}.
     *
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param serviceUrl Dirección de {@code service} que debe procesarse.
     * @param internalServiceToken Valor de {@code internalServiceToken} utilizado por la operación.
     * @param requestTimeout Valor de {@code requestTimeout} utilizado por la operación.
     */
    @Autowired
    public SemanticSearchClient(
            ObjectMapper objectMapper,
            @Value("${app.semantic-service-url}") String serviceUrl,
            @Value("${app.semantic-internal-service-token}") String internalServiceToken,
            @Value("${app.semantic-request-timeout}") Duration requestTimeout) {
        this(
                HttpClient.newBuilder().connectTimeout(requestTimeout).build(),
                objectMapper,
                serviceUrl,
                internalServiceToken,
                requestTimeout,
                true);
    }

    /**
     * Inicializa una instancia de {@code SemanticSearchClient}.
     *
     * @param httpClient Valor de {@code httpClient} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param serviceUrl Dirección de {@code service} que debe procesarse.
     * @param internalServiceToken Valor de {@code internalServiceToken} utilizado por la operación.
     * @param requestTimeout Valor de {@code requestTimeout} utilizado por la operación.
     * @param enabled Valor de {@code enabled} utilizado por la operación.
     */
    SemanticSearchClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String serviceUrl,
            String internalServiceToken,
            Duration requestTimeout,
            boolean enabled) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.replaceAll("/+$", "");
        this.internalServiceToken = internalServiceToken == null ? "" : internalServiceToken;
        this.requestTimeout = requestTimeout;
        this.enabled = enabled;
    }

    /**
     * Ejecuta la operación {@code disabled}.
     *
     * @return Resultado producido por {@code disabled}.
     */
    static SemanticSearchClient disabled() {
        return new SemanticSearchClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                "",
                "",
                Duration.ofSeconds(1),
                false);
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
        if (!enabled) {
            return SemanticCandidateSet.lexical(
                    CatalogSearchMode.SEMANTIC,
                    "semantic_service_unavailable");
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    request(query),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                return fallback("semantic_unauthorized");
            }
            if (response.statusCode() >= 500) {
                return fallback("semantic_index_unavailable");
            }
            if (response.statusCode() >= 400) {
                return fallback("semantic_request_rejected");
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
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fallback("semantic_request_interrupted");
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
    private HttpRequest request(String query) throws JsonProcessingException {
        String body = objectMapper.writeValueAsString(
                new SemanticRequest(query.trim(), FUNCTIONAL_CANDIDATE_LIMIT));
        return HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + "/internal/v1/semantic/search"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("X-Internal-Service-Token", internalServiceToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
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
