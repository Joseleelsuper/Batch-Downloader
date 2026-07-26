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

@Component
public class SemanticSearchClient {
    /**
     * The semantic response must enumerate the complete public catalog because
     * MySQL remains authoritative for status, facets and pagination.
     */
    private static final int FUNCTIONAL_CANDIDATE_LIMIT = 20000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String serviceUrl;
    private final String internalServiceToken;
    private final Duration requestTimeout;
    private final boolean enabled;

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

    static SemanticSearchClient disabled() {
        return new SemanticSearchClient(
                HttpClient.newHttpClient(),
                new ObjectMapper(),
                "",
                "",
                Duration.ofSeconds(1),
                false);
    }

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

    private SemanticCandidateSet fallback(String reason) {
        return SemanticCandidateSet.lexical(CatalogSearchMode.SEMANTIC, reason);
    }

    private record SemanticRequest(String query, int limit) {}

    private record SemanticResponse(
            List<SemanticCandidate> candidates,
            String modelVersion,
            String indexVersion,
            boolean truncated) {
        private SemanticResponse {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    private record SemanticCandidate(String appId, int rank, double similarity) {}
}
