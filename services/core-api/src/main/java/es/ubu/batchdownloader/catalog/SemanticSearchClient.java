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
 * Obtiene candidatos de Semantic y degrada toda la petición a léxica ante fallos, truncamiento o
 * versiones incompletas, sin propagar contenido interno al navegador.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.catalog.SemanticCandidateSet
 * @see es.ubu.batchdownloader.catalog.CatalogController
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @since 0.1.0
 * @version 0.1.0
 * @category Catálogo
 */
@Component
public class SemanticSearchClient {
    /**
     * Constante que define {@code FUNCTIONAL_CANDIDATE_LIMIT}.
     */
    private static final int FUNCTIONAL_CANDIDATE_LIMIT = 20000;

    /**
     * Ejecutor de la consulta interna autenticada.
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
     * Conecta serialización y transporte interno autenticado con plazo y métricas opcionales.
     *
     * @param objectMapper Conversor JSON de eventos públicos o respuestas del servicio semántico.
     * @param serviceUrl Origen interno de Semantic; se eliminan barras finales antes de añadir la
     *     ruta.
     * @param internalServiceToken Credencial de autenticación entre Core y Semantic.
     * @param requestTimeout Tiempo máximo de conexión y ejecución de la consulta semántica.
     * @param registry Registro de métricas opcional; null conserva la cadena sin instrumentación.
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
     * Conecta serialización y transporte interno autenticado con plazo y métricas opcionales.
     *
     * @param httpClient Transporte HTTP sustituible en pruebas para controlar respuestas y fallos.
     * @param objectMapper Conversor JSON de eventos públicos o respuestas del servicio semántico.
     * @param serviceUrl Origen interno de Semantic; se eliminan barras finales antes de añadir la
     *     ruta.
     * @param internalServiceToken Credencial de autenticación entre Core y Semantic.
     * @param requestTimeout Tiempo máximo de conexión y ejecución de la consulta semántica.
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

    /**
     * Conecta serialización y transporte interno autenticado con plazo y métricas opcionales.
     *
     * @param objectMapper Conversor JSON de eventos públicos o respuestas del servicio semántico.
     * @param serviceUrl Origen interno de Semantic; se eliminan barras finales antes de añadir la
     *     ruta.
     * @param executor Cadena de transporte, token de servicio y plazo de ejecución HTTP.
     */
    private SemanticSearchClient(
            ObjectMapper objectMapper,
            String serviceUrl,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.replaceAll("/+$", "");
    }

    /**
     * Evita llamadas para modo léxico o texto vacío y acepta hasta veinte mil candidatos no
     * truncados con modelo e índice identificados; cualquier error produce un motivo seguro de
     * degradación.
     *
     * @param requestedMode Modo de búsqueda solicitado por el cliente.
     * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
     * @return conjunto completo semántico o decisión léxica que debe compartirse con total y
     *     facetas.
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
     * Prepara un POST JSON autenticable a la búsqueda interna con texto recortado y límite
     * funcional de veinte mil candidatos.
     *
     * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
     * @return petición sin datos de filtros que siguen siendo autoridad de MySQL.
     * @throws com.fasterxml.jackson.core.JsonProcessingException si no puede serializarse la
     *     solicitud.
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

    /**
     * Compone transporte, credencial interna y plazo común de ejecución.
     *
     * @param client Transporte JDK al que se añaden políticas de autenticación y tiempo máximo.
     * @param token Credencial de servicio; null se representa como cabecera vacía.
     * @param timeout Tiempo máximo de conexión y ejecución de la petición interna.
     * @return ejecutor de la consulta interna autenticada.
     */
    private static InternalHttpExecutor executor(
            HttpClient client,
            String token,
            Duration timeout) {
        InternalHttpExecutor result = new JdkInternalHttpExecutor(client);
        result = new ServiceTokenInternalHttpExecutor(
                result, token == null ? "" : token);
        return new TimeoutInternalHttpExecutor(result, timeout);
    }

    /**
     * Configura tiempo de conexión y añade medición de duración y resultado cuando existe registro.
     *
     * @param token Credencial de servicio; null se representa como cabecera vacía.
     * @param timeout Tiempo máximo de conexión y ejecución de la petición interna.
     * @param registry Registro de métricas opcional; null conserva la cadena sin instrumentación.
     * @return cadena de transporte y políticas del cliente semántico.
     */
    private static InternalHttpExecutor instrumentedExecutor(
            String token,
            Duration timeout,
            MeterRegistry registry) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        InternalHttpExecutor result = executor(client, token, timeout);
        return registry == null ? result : new MeteredInternalHttpExecutor(result, registry);
    }

    /**
     * Conserva que se solicitó modo semántico y declara que la petición completa debe resolverse
     * léxicamente.
     *
     * @param reason Código seguro del motivo por el que toda la búsqueda pasa a modo léxico.
     * @return decisión de degradación con el código recibido.
     */
    private SemanticCandidateSet fallback(String reason) {
        return SemanticCandidateSet.lexical(CatalogSearchMode.SEMANTIC, reason);
    }

    /**
     * Reconoce únicamente el código de consulta demasiado corta o su error de validación FastAPI;
     * cualquier otro cuerpo se clasifica genéricamente.
     *
     * @param body Cuerpo de error del servicio semántico, usado solo para clasificar códigos
     *     conocidos.
     * @return semantic_query_too_short o semantic_request_rejected sin exponer el cuerpo.
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
     * Exige tipo string_too_short localizado exactamente en body.query para reconocer esa causa de
     * rechazo.
     *
     * @param error Elemento de validación de FastAPI con tipo y localización del campo rechazado.
     * @return true si coincide el error conocido de longitud de consulta.
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
     * Transporta el texto de búsqueda y el límite funcional de candidatos del contrato interno de
     * Semantic.
     *
     * @param query Texto de búsqueda; null o blanco no impone filtro léxico ni solicita embeddings.
     * @param limit Máximo funcional de veinte mil candidatos para poder aplicar filtros y
     *     paginación completos.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    private record SemanticRequest(String query, int limit) {}

    /**
     * Conserva candidatos y versiones declarados por Semantic para comprobar integridad antes de
     * aplicar sus resultados.
     *
     * @param candidates Candidatos devueltos; una lista ausente se normaliza a vacía.
     * @param modelVersion Modelo de embeddings usado; null cuando se aplica búsqueda léxica.
     * @param indexVersion Versión del índice semántico usado; null cuando se aplica búsqueda
     *     léxica.
     * @param truncated Indica que Semantic no pudo devolver todos los candidatos del conjunto.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    private record SemanticResponse(
            List<SemanticCandidate> candidates,
            String modelVersion,
            String indexVersion,
            boolean truncated) {
        /**
         * Copia la lista recibida para conservar una respuesta estable y normaliza null a lista
         * vacía.
         *
         * @param candidates Candidatos de la respuesta interna.
         * @param modelVersion Modelo de embeddings usado; null cuando se aplica búsqueda léxica.
         * @param indexVersion Versión del índice semántico usado; null cuando se aplica búsqueda
         *     léxica.
         * @param truncated Indica que Semantic no pudo devolver todos los candidatos del conjunto.
         */
        private SemanticResponse {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * Relaciona una aplicación con su rango y similitud sin sustituir los filtros ni permisos de
     * MySQL.
     *
     * @param appId UUID de la aplicación; las rutas textuales también admiten slug o identificador
     *     Winstall.
     * @param rank Posición del candidato que Core utiliza para desempatar en el modo semántico.
     * @param similarity Similitud comunicada por Semantic; la autoridad de filtros y visibilidad
     *     sigue siendo MySQL.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Catálogo
     */
    private record SemanticCandidate(String appId, int rank, double similarity) {}
}
