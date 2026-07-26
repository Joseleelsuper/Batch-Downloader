package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import es.ubu.batchdownloader.common.ConflictException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Authenticated Core-to-semantic-service boundary for administrative operations. */
@Component
public class SemanticAdminClient {
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String serviceUrl;
    private final String internalServiceToken;
    private final Duration requestTimeout;

    @Autowired
    public SemanticAdminClient(
            ObjectMapper objectMapper,
            @Value("${app.semantic-service-url}") String serviceUrl,
            @Value("${app.semantic-internal-service-token}") String internalServiceToken,
            @Value("${app.semantic-admin-request-timeout}") Duration requestTimeout) {
        this(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                objectMapper,
                serviceUrl,
                internalServiceToken,
                requestTimeout);
    }

    SemanticAdminClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String serviceUrl,
            String internalServiceToken,
            Duration requestTimeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.serviceUrl = serviceUrl.replaceAll("/+$", "");
        this.internalServiceToken = internalServiceToken;
        this.requestTimeout = requestTimeout;
    }

    public Result get(String path) {
        return send("GET", path, null, null, null);
    }

    public Result post(
            String path,
            JsonNode body,
            String actor,
            String idempotencyKey) {
        return send("POST", path, body, actor, idempotencyKey);
    }

    public Result delete(
            String path,
            String actor,
            String idempotencyKey) {
        return send("DELETE", path, null, actor, idempotencyKey);
    }

    private Result send(
            String method,
            String path,
            JsonNode body,
            String actor,
            String idempotencyKey) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(serviceUrl + path))
                .timeout(requestTimeout)
                .header("X-Internal-Service-Token", internalServiceToken);
        if (actor != null && !actor.isBlank()) {
            builder.header("X-Admin-Actor", actor);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(write(body)));
        try {
            HttpResponse<String> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                throw unavailable("semantic_admin_internal_unauthorized");
            }
            JsonNode payload = response.body().isBlank()
                    ? NullNode.getInstance()
                    : objectMapper.readTree(response.body());
            return new Result(response.statusCode(), payload);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("semantic_admin_interrupted");
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable("semantic_admin_unavailable");
        }
    }

    private String write(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException exception) {
            throw unavailable("semantic_admin_serialization_failed");
        }
    }

    private ConflictException unavailable(String code) {
        return new ConflictException(
                code,
                "No se pudo completar la operación administrativa de IA semántica.");
    }

    public record Result(int status, JsonNode body) {}
}
