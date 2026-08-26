package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerApplyRequest;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerApplyResult;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerInspection;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerInspectionRequest;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscovery;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryApplyRequest;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryApplyResult;
import es.ubu.batchdownloader.admin.AdminDtos.WebsiteAppDiscoveryRequest;
import es.ubu.batchdownloader.common.BadRequestException;
import es.ubu.batchdownloader.common.ConflictException;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.ServiceUnavailableException;
import es.ubu.batchdownloader.common.UnprocessableEntityException;
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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Encapsula la comunicación externa realizada por {@code ScraperInternalClient}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class ScraperInternalClient {
    /**
     * Ejecutor HTTP interno con políticas transversales compuestas.
     */
    private final InternalHttpExecutor executor;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code ScraperInternalClient}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code scraperApiUrl} mantenido por {@code ScraperInternalClient}.
     */
    private final String scraperApiUrl;
    /**
     * Inicializa una instancia de {@code ScraperInternalClient}.
     *
     * @param objectMapper Valor de {@code objectMapper} utilizado por la operación.
     * @param scraperApiUrl Dirección de {@code scraperApi} que debe procesarse.
     * @param internalServiceToken Valor de {@code internalServiceToken} utilizado por la operación.
     * @param registry Registro opcional para observar las llamadas internas.
     */
    @Autowired
    public ScraperInternalClient(
            ObjectMapper objectMapper,
            @Value("${app.scraper-api-url}") String scraperApiUrl,
            @Value("${app.scraper-internal-service-token}") String internalServiceToken,
            @Nullable MeterRegistry registry) {
        this(
                objectMapper,
                scraperApiUrl,
                instrumentedExecutor(internalServiceToken, registry));
    }

    /**
     * Constructor conservado para pruebas y consumidores sin registro de métricas.
     *
     * @param objectMapper serializador de los contratos internos.
     * @param scraperApiUrl dirección base del scraper.
     * @param internalServiceToken credencial compartida entre servicios.
     */
    public ScraperInternalClient(
            ObjectMapper objectMapper,
            String scraperApiUrl,
            String internalServiceToken) {
        this(objectMapper, scraperApiUrl, executor(internalServiceToken));
    }

    private ScraperInternalClient(
            ObjectMapper objectMapper,
            String scraperApiUrl,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.scraperApiUrl = scraperApiUrl.replaceAll("/+$", "");
    }

    /**
     * Ejecuta la operación {@code generateDescription}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Resultado producido por {@code generateDescription}.
     */
    public DescriptionGeneration generateDescription(String appId) {
        return post(
                "/internal/v1/content/descriptions/generate",
                write(new GenerateDescriptionRequest(appId)),
                DescriptionGeneration.class,
                "description_generation_failed");
    }

    /**
     * Encola la operación solicitada mediante {@code enqueueMissingDescriptions}.
     *
     * @return Resultado producido por {@code enqueueMissingDescriptions}.
     */
    public ContentEnqueueResult enqueueMissingDescriptions() {
        return post(
                "/internal/v1/content/descriptions/enqueue-missing",
                "",
                ContentEnqueueResult.class,
                "description_enqueue_failed");
    }

    /**
     * Crea el recurso solicitado mediante {@code createManualInstallerInspection}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code createManualInstallerInspection}.
     */
    public ManualInstallerInspection createManualInstallerInspection(
            String appId,
            ManualInstallerInspectionRequest request) {
        return post(
                manualInspectionPath(appId),
                write(request),
                ManualInstallerInspection.class,
                "manual_installer_inspection_failed");
    }

    /**
     * Ejecuta la operación {@code currentManualInstallerInspection}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Resultado producido por {@code currentManualInstallerInspection}.
     */
    public ManualInstallerInspection currentManualInstallerInspection(String appId) {
        return get(
                manualInspectionPath(appId) + "/current",
                ManualInstallerInspection.class,
                "manual_installer_inspection_failed");
    }

    /**
     * Ejecuta la operación {@code manualInstallerInspection}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param inspectionId Identificador de {@code inspection} utilizado por la operación.
     * @return Resultado producido por {@code manualInstallerInspection}.
     */
    public ManualInstallerInspection manualInstallerInspection(
            String appId,
            String inspectionId) {
        return get(
                manualInspectionPath(appId) + "/" + uuidSegment(inspectionId),
                ManualInstallerInspection.class,
                "manual_installer_inspection_failed");
    }

    /**
     * Ejecuta la operación {@code applyManualInstallerInspection}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @param inspectionId Identificador de {@code inspection} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code applyManualInstallerInspection}.
     */
    public ManualInstallerApplyResult applyManualInstallerInspection(
            String appId,
            String inspectionId,
            ManualInstallerApplyRequest request) {
        return post(
                manualInspectionPath(appId) + "/" + uuidSegment(inspectionId) + "/apply",
                write(request),
                ManualInstallerApplyResult.class,
                "manual_installer_apply_failed");
    }

    /**
     * Crea el recurso solicitado mediante {@code createWebsiteAppDiscovery}.
     *
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code createWebsiteAppDiscovery}.
     */
    public WebsiteAppDiscovery createWebsiteAppDiscovery(
            WebsiteAppDiscoveryRequest request) {
        return post(
                websiteDiscoveryPath(),
                write(request),
                WebsiteAppDiscovery.class,
                "website_app_discovery_failed");
    }

    /**
     * Ejecuta la operación {@code websiteAppDiscovery}.
     *
     * @param discoveryId Identificador de {@code discovery} utilizado por la operación.
     * @return Resultado producido por {@code websiteAppDiscovery}.
     */
    public WebsiteAppDiscovery websiteAppDiscovery(String discoveryId) {
        return get(
                websiteDiscoveryPath() + "/" + uuidSegment(discoveryId),
                WebsiteAppDiscovery.class,
                "website_app_discovery_failed");
    }

    /**
     * Ejecuta la operación {@code applyWebsiteAppDiscovery}.
     *
     * @param discoveryId Identificador de {@code discovery} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @return Resultado producido por {@code applyWebsiteAppDiscovery}.
     */
    public WebsiteAppDiscoveryApplyResult applyWebsiteAppDiscovery(
            String discoveryId,
            WebsiteAppDiscoveryApplyRequest request) {
        return post(
                websiteDiscoveryPath() + "/" + uuidSegment(discoveryId) + "/apply",
                write(request),
                WebsiteAppDiscoveryApplyResult.class,
                "website_app_discovery_apply_failed");
    }

    /**
     * Ejecuta la operación {@code manualInspectionPath}.
     *
     * @param appId Identificador de {@code app} utilizado por la operación.
     * @return Resultado producido por {@code manualInspectionPath}.
     */
    private String manualInspectionPath(String appId) {
        return "/internal/v1/admin/apps/" + uuidSegment(appId)
                + "/manual-installer-inspections";
    }

    /**
     * Ejecuta la operación {@code websiteDiscoveryPath}.
     *
     * @return Resultado producido por {@code websiteDiscoveryPath}.
     */
    private String websiteDiscoveryPath() {
        return "/internal/v1/admin/app-discoveries";
    }

    /**
     * Ejecuta la operación {@code uuidSegment}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code uuidSegment}.
     * @throws BadRequestException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private String uuidSegment(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("invalid_identifier", "El identificador no es válido.");
        }
    }

    /**
     * Obtiene el resultado solicitado mediante {@code get}.
     *
     * @param <T> Parámetro de tipo utilizado por la operación.
     * @param path Ruta del recurso que debe procesarse.
     * @param responseType Valor de {@code responseType} utilizado por la operación.
     * @param failureCode Valor de {@code failureCode} utilizado por la operación.
     * @return Resultado producido por {@code get}.
     */
    private <T> T get(String path, Class<T> responseType, String failureCode) {
        return send("GET", path, "", responseType, failureCode);
    }

    /**
     * Ejecuta la operación {@code post}.
     *
     * @param <T> Parámetro de tipo utilizado por la operación.
     * @param path Ruta del recurso que debe procesarse.
     * @param body Cuerpo recibido por la solicitud.
     * @param responseType Valor de {@code responseType} utilizado por la operación.
     * @param failureCode Valor de {@code failureCode} utilizado por la operación.
     * @return Resultado producido por {@code post}.
     */
    private <T> T post(
            String path,
            String body,
            Class<T> responseType,
            String failureCode) {
        return send("POST", path, body, responseType, failureCode);
    }

    /**
     * Envía el contenido solicitado mediante {@code send}.
     *
     * @param <T> Parámetro de tipo utilizado por la operación.
     * @param method Valor de {@code method} utilizado por la operación.
     * @param path Ruta del recurso que debe procesarse.
     * @param body Cuerpo recibido por la solicitud.
     * @param responseType Valor de {@code responseType} utilizado por la operación.
     * @param failureCode Valor de {@code failureCode} utilizado por la operación.
     * @return Resultado producido por {@code send}.
     */
    private <T> T send(
            String method,
            String path,
            String body,
            Class<T> responseType,
            String failureCode) {
        InternalHttpRequest request = new InternalHttpRequest(
                "scraper",
                failureCode,
                method,
                URI.create(scraperApiUrl + path),
                "GET".equals(method) ? null : body);
        if (!body.isEmpty()) {
            request = request.withHeader("Content-Type", "application/json");
        }
        try {
            InternalHttpResponse response = executor.execute(request);
            if (response.statusCode() >= 400) {
                throw upstreamFailure(response.statusCode(), response.body(), failureCode);
            }
            if (responseType == Void.class) {
                return null;
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (InternalHttpTransportException | IOException exception) {
            throw failure(failureCode);
        } catch (IllegalArgumentException exception) {
            throw failure(failureCode);
        }
    }

    private static InternalHttpExecutor executor(String token) {
        InternalHttpExecutor result = new JdkInternalHttpExecutor(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        result = new ServiceTokenInternalHttpExecutor(result, token);
        return new TimeoutInternalHttpExecutor(result, Duration.ofSeconds(30));
    }

    private static InternalHttpExecutor instrumentedExecutor(
            String token,
            MeterRegistry registry) {
        InternalHttpExecutor result = executor(token);
        return registry == null ? result : new MeteredInternalHttpExecutor(result, registry);
    }

    /**
     * Ejecuta la operación {@code write}.
     *
     * @param value Valor que debe procesarse.
     * @return Resultado producido por {@code write}.
     */
    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw failure("scraper_request_serialization_failed");
        }
    }

    /**
     * Ejecuta la operación {@code upstreamFailure}.
     *
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param body Cuerpo recibido por la solicitud.
     * @param fallbackCode Valor de {@code fallbackCode} utilizado por la operación.
     * @return Resultado producido por {@code upstreamFailure}.
     */
    private RuntimeException upstreamFailure(int status, String body, String fallbackCode) {
        String code = upstreamCode(body, fallbackCode);
        String message = "No se pudo completar la operación interna del scraper.";
        return switch (status) {
            case 400 -> new BadRequestException(code, message);
            case 404 -> new NotFoundException(code, message);
            case 409 -> new ConflictException(code, message);
            case 422 -> new UnprocessableEntityException(code, message);
            case 401, 403 -> new ServiceUnavailableException(
                    "scraper_internal_auth_failed",
                    message);
            default -> new ServiceUnavailableException(code, message);
        };
    }

    /**
     * Ejecuta la operación {@code upstreamCode}.
     *
     * @param body Cuerpo recibido por la solicitud.
     * @param fallbackCode Valor de {@code fallbackCode} utilizado por la operación.
     * @return Resultado producido por {@code upstreamCode}.
     */
    private String upstreamCode(String body, String fallbackCode) {
        try {
            JsonNode detail = objectMapper.readTree(body).path("detail");
            JsonNode code = detail.isObject() ? detail.path("code") : null;
            if (code != null
                    && code.isTextual()
                    && code.textValue().matches("[a-z0-9_:-]{1,120}")) {
                return code.textValue();
            }
        } catch (IOException ignored) {
            // El cuerpo del servicio remoto no es fiable y nunca se copia en errores de Core.
        }
        return fallbackCode;
    }

    /**
     * Ejecuta la operación {@code failure}.
     *
     * @param code Valor de {@code code} utilizado por la operación.
     * @return Resultado producido por {@code failure}.
     */
    private ServiceUnavailableException failure(String code) {
        return new ServiceUnavailableException(
                code,
                "No se pudo completar la operación interna del scraper.");
    }

    /**
     * Representa los datos inmutables de {@code ContentEnqueueResult}.
     *
     * @param matched Valor de {@code matched} incluido en el record.
     * @param enqueued Valor de {@code enqueued} incluido en el record.
     * @param alreadyActive Valor de {@code alreadyActive} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record ContentEnqueueResult(int matched, int enqueued, int alreadyActive) {}

    /**
     * Representa los datos inmutables de {@code DescriptionGeneration}.
     *
     * @param jobId Valor de {@code jobId} incluido en el record.
     * @param status Valor de {@code status} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    public record DescriptionGeneration(String jobId, String status) {}

    /**
     * Representa los datos inmutables de {@code GenerateDescriptionRequest}.
     *
     * @param appId Valor de {@code appId} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    private record GenerateDescriptionRequest(String appId) {}
}
