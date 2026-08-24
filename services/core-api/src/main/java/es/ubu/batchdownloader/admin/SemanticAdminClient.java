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

/**
 * Encapsula la comunicación externa realizada por {@code SemanticAdminClient}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class SemanticAdminClient {
    /**
     * Ejecutor HTTP interno con políticas transversales compuestas.
     */
    private final InternalHttpExecutor executor;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code SemanticAdminClient}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code serviceUrl} mantenido por {@code SemanticAdminClient}.
     */
    private final String serviceUrl;
    /**
     * Inicializa una instancia de {@code SemanticAdminClient}.
     *
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param serviceUrl Dirección de {@code service} que debe procesarse.
     * @param internalServiceToken Valor de {@code internalServiceToken} utilizado por la operación.
     * @param requestTimeout Valor de {@code requestTimeout} utilizado por la operación.
     * @param registry Registro opcional para observar las llamadas internas.
     */
    @Autowired
    public SemanticAdminClient(
            ObjectMapper objectMapper,
            @Value("${app.semantic-service-url}") String serviceUrl,
            @Value("${app.semantic-internal-service-token}") String internalServiceToken,
            @Value("${app.semantic-admin-request-timeout}") Duration requestTimeout,
            @Nullable MeterRegistry registry) {
        this(
                objectMapper,
                serviceUrl,
                instrumentedExecutor(internalServiceToken, requestTimeout, registry));
    }

    /**
     * Inicializa una instancia de {@code SemanticAdminClient}.
     *
     * @param httpClient Valor de {@code httpClient} utilizado por la operación.
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param serviceUrl Dirección de {@code service} que debe procesarse.
     * @param internalServiceToken Valor de {@code internalServiceToken} utilizado por la operación.
     * @param requestTimeout Valor de {@code requestTimeout} utilizado por la operación.
     */
    SemanticAdminClient(
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

    private SemanticAdminClient(
            ObjectMapper objectMapper,
            String serviceUrl,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.serviceUrl = serviceUrl.replaceAll("/+$", "");
    }

    /**
     * Obtiene el resultado solicitado mediante {@code get}.
     *
     * @param path Ruta del recurso que debe procesarse.
     * @return Resultado producido por {@code get}.
     */
    public Result get(String path) {
        return send("GET", path, null, null, null);
    }

    /**
     * Ejecuta la operación {@code post}.
     *
     * @param path Ruta del recurso que debe procesarse.
     * @param body Cuerpo recibido por la solicitud.
     * @param actor Identidad del actor que solicita la operación.
     * @param idempotencyKey Valor de {@code idempotencyKey} utilizado por la operación.
     * @return Resultado producido por {@code post}.
     */
    public Result post(
            String path,
            JsonNode body,
            String actor,
            String idempotencyKey) {
        return send("POST", path, body, actor, idempotencyKey);
    }

    /**
     * Elimina el recurso solicitado mediante {@code delete}.
     *
     * @param path Ruta del recurso que debe procesarse.
     * @param actor Identidad del actor que solicita la operación.
     * @param idempotencyKey Valor de {@code idempotencyKey} utilizado por la operación.
     * @return Resultado producido por {@code delete}.
     */
    public Result delete(
            String path,
            String actor,
            String idempotencyKey) {
        return send("DELETE", path, null, actor, idempotencyKey);
    }

    /**
     * Envía el contenido solicitado mediante {@code send}.
     *
     * @param method Valor de {@code method} utilizado por la operación.
     * @param path Ruta del recurso que debe procesarse.
     * @param body Cuerpo recibido por la solicitud.
     * @param actor Identidad del actor que solicita la operación.
     * @param idempotencyKey Valor de {@code idempotencyKey} utilizado por la operación.
     * @return Resultado producido por {@code send}.
     */
    private Result send(
            String method,
            String path,
            JsonNode body,
            String actor,
            String idempotencyKey) {
        InternalHttpRequest request = new InternalHttpRequest(
                "semantic",
                "admin_" + method.toLowerCase(java.util.Locale.ROOT),
                method,
                URI.create(serviceUrl + path),
                body == null ? null : write(body));
        if (actor != null && !actor.isBlank()) {
            request = request.withHeader("X-Admin-Actor", actor);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request = request.withHeader("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            request = request.withHeader("Content-Type", "application/json");
        }
        try {
            InternalHttpResponse response = executor.execute(request);
            if (response.statusCode() == 401) {
                throw unavailable("semantic_admin_internal_unauthorized");
            }
            JsonNode payload = response.body().isBlank()
                    ? NullNode.getInstance()
                    : objectMapper.readTree(response.body());
            return new Result(response.statusCode(), payload);
        } catch (InternalHttpTransportException exception) {
            throw unavailable(exception.interrupted()
                    ? "semantic_admin_interrupted"
                    : "semantic_admin_unavailable");
        } catch (IOException | IllegalArgumentException exception) {
            throw unavailable("semantic_admin_unavailable");
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
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        InternalHttpExecutor result = executor(client, token, timeout);
        return registry == null ? result : new MeteredInternalHttpExecutor(result, registry);
    }

    /**
     * Ejecuta la operación {@code write}.
     *
     * @param body Cuerpo recibido por la solicitud.
     * @return Resultado producido por {@code write}.
     */
    private String write(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException exception) {
            throw unavailable("semantic_admin_serialization_failed");
        }
    }

    /**
     * Ejecuta la operación {@code unavailable}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @return Resultado producido por {@code unavailable}.
     */
    private ConflictException unavailable(String code) {
        return new ConflictException(
                code,
                "No se pudo completar la operación administrativa de IA semántica.");
    }

    /**
     * Representa los datos inmutables de {@code Result}.
     *
     * @param status Valor de {@code status} incluido en el record.
     * @param body Valor de {@code body} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record Result(int status, JsonNode body) {}
}
