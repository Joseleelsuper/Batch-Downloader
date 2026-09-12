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
 * Reenvía operaciones administrativas al servicio semántico con actor e idempotencia, conservando
 * su estado HTTP y JSON salvo los fallos internos traducidos a conflicto.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminSemanticController
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @since 0.1.0
 * @version 0.1.0
 * @category Comunicación administrativa
 */
@Component
public class SemanticAdminClient {
    /**
     * Ejecutor autenticado y acotado en tiempo.
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
     * Construye el cliente de producción con token, timeout configurable y métricas opcionales.
     *
     * @param objectMapper Serializador de peticiones y lector del JSON de respuesta del servicio
     *     interno.
     * @param serviceUrl URL base del servicio semántico; se eliminan las barras finales.
     * @param internalServiceToken Credencial interna añadida por el decorador HTTP, sin exponerla
     *     al cliente público.
     * @param requestTimeout Duración máxima configurada para la operación administrativa remota.
     * @param registry Registro opcional de métricas; null conserva el ejecutor sin instrumentación.
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
     * Permite inyectar el cliente HTTP en pruebas conservando autenticación y timeout.
     *
     * @param httpClient Cliente HTTP inyectado para controlar conexiones durante las pruebas.
     * @param objectMapper Serializador de peticiones y lector del JSON de respuesta del servicio
     *     interno.
     * @param serviceUrl URL base del servicio semántico; se eliminan las barras finales.
     * @param internalServiceToken Credencial interna añadida por el decorador HTTP, sin exponerla
     *     al cliente público.
     * @param requestTimeout Duración máxima configurada para la operación administrativa remota.
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

    /**
     * Conserva el ejecutor ya compuesto y normaliza la URL base del servicio semántico.
     *
     * @param objectMapper Serializador de peticiones y lector del JSON de respuesta del servicio
     *     interno.
     * @param serviceUrl URL base del servicio semántico; se eliminan las barras finales.
     * @param executor Transporte ya compuesto con autenticación, timeout y las métricas que
     *     correspondan.
     */
    private SemanticAdminClient(
            ObjectMapper objectMapper,
            String serviceUrl,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.serviceUrl = serviceUrl.replaceAll("/+$", "");
    }

    /**
     * Consulta un recurso administrativo sin cuerpo, actor ni clave de idempotencia.
     *
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @return estado HTTP y cuerpo JSON del servicio semántico.
     */
    public Result get(String path) {
        return send("GET", path, null, null, null);
    }

    /**
     * Envía una acción administrativa con cuerpo y las cabeceras opcionales de actor e
     * idempotencia.
     *
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @param actor UUID textual del administrador; se omite la cabecera cuando falta o está vacío.
     * @param idempotencyKey Clave que vincula reintentos de una misma operación; se omite cuando no
     *     se proporciona.
     * @return estado HTTP y cuerpo JSON de la operación remota.
     */
    public Result post(
            String path,
            JsonNode body,
            String actor,
            String idempotencyKey) {
        return send("POST", path, body, actor, idempotencyKey);
    }

    /**
     * Solicita borrar un recurso administrativo con actor e idempotencia y sin cuerpo.
     *
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @param actor UUID textual del administrador; se omite la cabecera cuando falta o está vacío.
     * @param idempotencyKey Clave que vincula reintentos de una misma operación; se omite cuando no
     *     se proporciona.
     * @return estado HTTP y cuerpo JSON de la operación remota.
     */
    public Result delete(
            String path,
            String actor,
            String idempotencyKey) {
        return send("DELETE", path, null, actor, idempotencyKey);
    }

    /**
     * Adjunta las cabeceras presentes, ejecuta la petición y conserva los estados remotos excepto
     * 401. Traduce autenticación interna, transporte y JSON inválido al conflicto administrativo
     * existente.
     *
     * @param method Método HTTP explícito de la operación, elegido por el cliente interno.
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @param actor UUID textual del administrador; se omite la cabecera cuando falta o está vacío.
     * @param idempotencyKey Clave que vincula reintentos de una misma operación; se omite cuando no
     *     se proporciona.
     * @return estado remoto y JSON; un cuerpo vacío se representa con NullNode.
     * @throws es.ubu.batchdownloader.common.ConflictException si falla la autenticación interna, el
     *     transporte, la serialización o la interpretación del resultado.
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

    /**
     * Compone el transporte inyectado con autenticación de servicio y el timeout administrativo
     * recibido.
     *
     * @param client Cliente JDK sobre el que se añaden las políticas internas.
     * @param token Credencial utilizada para autenticar peticiones entre servicios.
     * @param timeout Duración máxima permitida para completar una petición interna.
     * @return ejecutor autenticado y acotado en tiempo.
     */
    private static InternalHttpExecutor executor(
            HttpClient client,
            String token,
            Duration timeout) {
        InternalHttpExecutor result = new JdkInternalHttpExecutor(client);
        result = new ServiceTokenInternalHttpExecutor(result, token);
        return new TimeoutInternalHttpExecutor(result, timeout);
    }

    /**
     * Crea conexiones con límite de cinco segundos y aplica token, timeout de operación y métricas
     * cuando están disponibles.
     *
     * @param token Credencial utilizada para autenticar peticiones entre servicios.
     * @param timeout Duración máxima permitida para completar una petición interna.
     * @param registry Registro opcional de métricas; null conserva el ejecutor sin instrumentación.
     * @return ejecutor de producción para administración semántica.
     */
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
     * Serializa el JSON de una operación semántica y traduce cualquier fallo Jackson al código de
     * serialización administrativa.
     *
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @return JSON listo para enviar.
     * @throws es.ubu.batchdownloader.common.ConflictException si no se puede serializar el cuerpo.
     */
    private String write(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException exception) {
            throw unavailable("semantic_admin_serialization_failed");
        }
    }

    /**
     * Construye el conflicto administrativo utilizado para comunicar un fallo interno semántico con
     * mensaje fijo.
     *
     * @param code Código estable de diagnóstico que puede comunicarse sin copiar el cuerpo remoto.
     * @return conflicto con el código seguro recibido.
     */
    private ConflictException unavailable(String code) {
        return new ConflictException(
                code,
                "No se pudo completar la operación administrativa de IA semántica.");
    }

    /**
     * Conserva juntos el estado HTTP y el cuerpo JSON para que el controlador entregue la respuesta
     * del servicio semántico.
     *
     * @param status Código de estado HTTP devuelto por el servicio interno.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Comunicación administrativa
     */
    public record Result(int status, JsonNode body) {}
}
