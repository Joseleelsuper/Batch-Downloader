package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import es.ubu.batchdownloader.common.ConflictException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** Consulta el estado semántico local para la pantalla administrativa de solo lectura. */
@Component
public class SemanticStatusClient {
    private final InternalHttpExecutor executor;
    private final ObjectMapper objectMapper;
    private final String serviceUrl;

    @Autowired
    public SemanticStatusClient(
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

    SemanticStatusClient(
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

    private SemanticStatusClient(
            ObjectMapper objectMapper,
            String serviceUrl,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.serviceUrl = serviceUrl.replaceAll("/+$", "");
    }

    public Result get() {
        InternalHttpRequest request = new InternalHttpRequest(
                "semantic",
                "status",
                "GET",
                URI.create(serviceUrl + "/semantic/health"),
                null);
        try {
            InternalHttpResponse response = executor.execute(request);
            if (response.statusCode() == 401) {
                throw unavailable("semantic_status_internal_unauthorized");
            }
            JsonNode body = response.body().isBlank()
                    ? NullNode.getInstance()
                    : objectMapper.readTree(response.body());
            return new Result(response.statusCode(), body == null ? NullNode.getInstance() : body);
        } catch (InternalHttpTransportException exception) {
            throw unavailable(exception.interrupted()
                    ? "semantic_status_interrupted"
                    : "semantic_status_unavailable");
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable("semantic_status_unavailable");
        }
    }

    private static InternalHttpExecutor executor(
            HttpClient client,
            String token,
            Duration timeout) {
        InternalHttpExecutor result = new JdkInternalHttpExecutor(client);
        result = new ServiceTokenInternalHttpExecutor(result, token);
        return new TimeoutInternalHttpExecutor(result, timeout);
    }

    private static InternalHttpExecutor instrumentedExecutor(
            String token,
            Duration timeout,
            MeterRegistry registry) {
        InternalHttpExecutor result = executor(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                token,
                timeout);
        return registry == null ? result : new MeteredInternalHttpExecutor(result, registry);
    }

    private static ConflictException unavailable(String code) {
        return new ConflictException(
                code,
                "No se pudo consultar el estado de IA semántica.");
    }

    public record Result(int status, JsonNode body) {}
}
