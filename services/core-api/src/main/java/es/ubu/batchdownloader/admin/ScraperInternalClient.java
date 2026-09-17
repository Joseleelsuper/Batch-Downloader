package es.ubu.batchdownloader.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerApplyRequest;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerApplyResult;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerInspection;
import es.ubu.batchdownloader.admin.InstallerInspectionDtos.ManualInstallerInspectionRequest;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscovery;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryApplyRequest;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryApplyResult;
import es.ubu.batchdownloader.admin.WebsiteDiscoveryDtos.WebsiteAppDiscoveryRequest;
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
 * Traduce operaciones administrativas de Core a peticiones autenticadas del scraper y convierte sus
 * errores a códigos públicos seguros sin copiar mensajes remotos.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.admin.AdminAppController
 * @see es.ubu.batchdownloader.admin.LinuxInstallAdminController
 * @see es.ubu.batchdownloader.common.http.InternalHttpExecutor
 * @since 0.1.0
 * @version 0.1.0
 * @category Comunicación administrativa
 */
@Component
public class ScraperInternalClient {
    @SuppressWarnings("java:S1075") // Ruta relativa fija del contrato; la base del scraper ya es configurable.
    private static final String APPS_PATH = "/apps/";

    /**
     * Consulta por GET el perfil Linux de un instalador exacto de la aplicación.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @param sourceRef UUID exacto del instalador cuyo perfil Linux se consulta o edita.
     * @return JSON del perfil o del estado de aprobación que expone el scraper.
     */
    public JsonNode readLinuxProfile(UUID appId, UUID sourceRef) {
        return readLinux(APPS_PATH + appId + "/sources/" + sourceRef + "/profile");
    }

    /**
     * Envía por PUT una edición del perfil Linux del instalador exacto.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @param sourceRef UUID exacto del instalador cuyo perfil Linux se consulta o edita.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @return JSON resultante de la validación y persistencia remotas.
     */
    public JsonNode writeLinuxProfile(UUID appId, UUID sourceRef, JsonNode body) {
        return writeLinux(APPS_PATH + appId + "/sources/" + sourceRef + "/profile", body);
    }

    /**
     * Consulta por GET las dependencias Linux registradas para una aplicación.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @return JSON de dependencias proporcionado por el scraper.
     */
    public JsonNode readLinuxDependencies(UUID appId) {
        return readLinux(APPS_PATH + appId + "/dependencies");
    }

    /**
     * Envía por PUT la revisión administrativa de dependencias Linux de una aplicación.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @return JSON de dependencias posterior a la escritura.
     */
    public JsonNode writeLinuxDependencies(UUID appId, JsonNode body) {
        return writeLinux(APPS_PATH + appId + "/dependencies", body);
    }

    /**
     * Añade el prefijo interno Linux y ejecuta una lectura explícita sin cuerpo.
     *
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @return respuesta JSON del scraper.
     */
    private JsonNode readLinux(String path) {
        return send("GET", "/internal/v1/linux" + path,
                "", JsonNode.class, "linux_profile_unavailable");
    }

    /**
     * Serializa el JSON y exige como máximo 128 Ki caracteres antes de enviarlo por PUT al prefijo
     * interno Linux.
     *
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @return respuesta JSON de la escritura.
     * @throws es.ubu.batchdownloader.common.BadRequestException si la representación del cuerpo
     *     supera 131072 caracteres.
     */
    private JsonNode writeLinux(String path, JsonNode body) {
        String payload = body.toString();
        if (payload.length() > 128 * 1024) {
            throw new BadRequestException("linux_profile_too_large", "La receta supera el límite permitido.");
        }
        return send("PUT", "/internal/v1/linux" + path,
                payload, JsonNode.class, "linux_profile_unavailable");
    }
    /**
     * Ejecutor interno autenticado con timeout.
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
     * Construye el cliente de producción con token interno, límites de tiempo y métricas
     * opcionales.
     *
     * @param objectMapper Serializador de peticiones y lector del JSON de respuesta del servicio
     *     interno.
     * @param scraperApiUrl URL base del scraper; se eliminan las barras finales antes de añadir
     *     rutas internas.
     * @param internalServiceToken Credencial interna añadida por el decorador HTTP, sin exponerla
     *     al cliente público.
     * @param registry Registro opcional de métricas; null conserva el ejecutor sin instrumentación.
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
     * Construye un cliente sin métricas manteniendo token interno y los mismos límites de tiempo.
     *
     * @param objectMapper Serializador de peticiones y lector del JSON de respuesta del servicio
     *     interno.
     * @param scraperApiUrl URL base del scraper; se eliminan las barras finales antes de añadir
     *     rutas internas.
     * @param internalServiceToken Credencial interna añadida por el decorador HTTP, sin exponerla
     *     al cliente público.
     */
    public ScraperInternalClient(
            ObjectMapper objectMapper,
            String scraperApiUrl,
            String internalServiceToken) {
        this(objectMapper, scraperApiUrl, executor(internalServiceToken));
    }

    /**
     * Conserva el transporte compuesto y normaliza la URL base para unir rutas internas.
     *
     * @param objectMapper Serializador de peticiones y lector del JSON de respuesta del servicio
     *     interno.
     * @param scraperApiUrl URL base del scraper; se eliminan las barras finales antes de añadir
     *     rutas internas.
     * @param executor Transporte ya compuesto con autenticación, timeout y las métricas que
     *     correspondan.
     */
    private ScraperInternalClient(
            ObjectMapper objectMapper,
            String scraperApiUrl,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.scraperApiUrl = scraperApiUrl.replaceAll("/+$", "");
    }

    /**
     * Solicita generación asíncrona de descripción para una aplicación y devuelve la identidad del
     * trabajo admitido.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @return UUID y estado del trabajo de contenido.
     */
    public DescriptionGeneration generateDescription(String appId) {
        return post(
                "/internal/v1/content/descriptions/generate",
                write(new GenerateDescriptionRequest(appId)),
                DescriptionGeneration.class,
                "description_generation_failed");
    }

    /**
     * Solicita encolar las descripciones que todavía faltan en el catálogo.
     *
     * @return recuentos de coincidencias, nuevas reservas y trabajos ya activos.
     */
    public ContentEnqueueResult enqueueMissingDescriptions() {
        return post(
                "/internal/v1/content/descriptions/enqueue-missing",
                "",
                ContentEnqueueResult.class,
                "description_enqueue_failed");
    }

    /**
     * Crea una inspección persistente de los instaladores aportados para una aplicación existente.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @param request Campos validados del flujo de inspección, descubrimiento o aplicación.
     * @return estado inicial de la inspección recuperable.
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
     * Consulta la inspección recuperable actual de la aplicación para reconstruir el flujo
     * administrativo.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @return estado proporcionado por el scraper para la inspección actual, o null si no existe
     *     una inspección abierta.
     */
    @Nullable
    public ManualInstallerInspection currentManualInstallerInspection(String appId) {
        return get(
                manualInspectionPath(appId) + "/current",
                ManualInstallerInspection.class,
                "manual_installer_inspection_failed");
    }

    /**
     * Consulta una inspección concreta usando UUID validados como segmentos de ruta.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @param inspectionId UUID textual de la inspección persistida que pertenece a la aplicación.
     * @return progreso, candidatos y diagnóstico persistidos de la inspección.
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
     * Solicita aplicar los candidatos inspeccionados junto a las decisiones y versión esperada de
     * la aplicación.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @param inspectionId UUID textual de la inspección persistida que pertenece a la aplicación.
     * @param request Campos validados del flujo de inspección, descubrimiento o aplicación.
     * @return referencias exactas publicadas y resultado de la actualización.
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
     * Crea un descubrimiento persistente a partir de páginas e instaladores propuestos sin publicar
     * todavía una aplicación.
     *
     * @param request Campos validados del flujo de inspección, descubrimiento o aplicación.
     * @return estado inicial del descubrimiento recuperable.
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
     * Recupera el avance y las propuestas de un descubrimiento identificado por UUID.
     *
     * @param discoveryId UUID textual del descubrimiento persistido que se consulta o aplica.
     * @return estado persistido que permite revisar y continuar el flujo.
     */
    public WebsiteAppDiscovery websiteAppDiscovery(String discoveryId) {
        return get(
                websiteDiscoveryPath() + "/" + uuidSegment(discoveryId),
                WebsiteAppDiscovery.class,
                "website_app_discovery_failed");
    }

    /**
     * Solicita publicar una aplicación a partir del descubrimiento y las decisiones administrativas
     * confirmadas.
     *
     * @param discoveryId UUID textual del descubrimiento persistido que se consulta o aplica.
     * @param request Campos validados del flujo de inspección, descubrimiento o aplicación.
     * @return aplicación creada o actualizada y las referencias exactas publicadas.
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
     * Construye el prefijo de inspecciones tras normalizar el UUID de aplicación.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @return ruta interna de inspecciones de esa aplicación.
     */
    private String manualInspectionPath(String appId) {
        return "/internal/v1/admin/apps/" + uuidSegment(appId)
                + "/manual-installer-inspections";
    }

    /**
     * Proporciona el prefijo único de los descubrimientos administrativos.
     *
     * @return ruta interna estable de descubrimientos.
     */
    private String websiteDiscoveryPath() {
        return "/internal/v1/admin/app-discoveries";
    }

    /**
     * Acepta únicamente un UUID y lo vuelve a serializar antes de incorporarlo a una ruta HTTP.
     *
     * @param value Objeto o texto que se serializa o valida antes de construir la petición.
     * @return UUID canónico sin separadores de ruta arbitrarios.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el texto no puede interpretarse
     *     como UUID.
     */
    private String uuidSegment(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("invalid_identifier", "El identificador no es válido.");
        }
    }

    /**
     * Ejecuta una lectura interna sin cuerpo con el tipo de respuesta y código de fallo de su caso
     * de uso.
     *
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @param responseType Tipo Jackson de la respuesta; Void indica que se descarta el cuerpo.
     * @param failureCode Código público seguro utilizado cuando no se obtiene un resultado válido.
     * @return respuesta deserializada.
     */
    private <T> T get(String path, Class<T> responseType, String failureCode) {
        return send("GET", path, "", responseType, failureCode);
    }

    /**
     * Envía una operación interna POST con su cuerpo ya serializado y el contrato de respuesta
     * esperado.
     *
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @param responseType Tipo Jackson de la respuesta; Void indica que se descarta el cuerpo.
     * @param failureCode Código público seguro utilizado cuando no se obtiene un resultado válido.
     * @return respuesta deserializada.
     */
    private <T> T post(
            String path,
            String body,
            Class<T> responseType,
            String failureCode) {
        return send("POST", path, body, responseType, failureCode);
    }

    /**
     * Ejecuta la petición autenticada, traduce estados de error y deserializa la respuesta.
     * Convierte fallos de transporte, formato o JSON a indisponibilidad segura.
     *
     * @param method Método HTTP explícito de la operación, elegido por el cliente interno.
     * @param path Ruta interna construida por Core y relativa a la URL base del servicio.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @param responseType Tipo Jackson de la respuesta; Void indica que se descarta el cuerpo.
     * @param failureCode Código público seguro utilizado cuando no se obtiene un resultado válido.
     * @return respuesta del tipo solicitado; null cuando se pidió Void.
     * @throws es.ubu.batchdownloader.common.ServiceUnavailableException si falla el transporte, la
     *     deserialización, la autenticación interna o el servicio remoto.
     * @throws es.ubu.batchdownloader.common.BadRequestException si el scraper responde 400.
     * @throws es.ubu.batchdownloader.common.NotFoundException si el scraper responde 404.
     * @throws es.ubu.batchdownloader.common.ConflictException si el scraper responde 409.
     * @throws es.ubu.batchdownloader.common.UnprocessableEntityException si el scraper responde
     *     422.
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

    /**
     * Compone transporte JDK con conexión de cinco segundos, token de servicio y límite de treinta
     * segundos por petición.
     *
     * @param token Credencial utilizada para autenticar peticiones entre servicios.
     * @return ejecutor interno autenticado con timeout.
     */
    private static InternalHttpExecutor executor(String token) {
        InternalHttpExecutor result = new JdkInternalHttpExecutor(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        result = new ServiceTokenInternalHttpExecutor(result, token);
        return new TimeoutInternalHttpExecutor(result, Duration.ofSeconds(30));
    }

    /**
     * Añade métricas al transporte del scraper cuando existe un registro disponible.
     *
     * @param token Credencial utilizada para autenticar peticiones entre servicios.
     * @param registry Registro opcional de métricas; null conserva el ejecutor sin instrumentación.
     * @return ejecutor con las mismas políticas de autenticación y tiempo.
     */
    private static InternalHttpExecutor instrumentedExecutor(
            String token,
            MeterRegistry registry) {
        InternalHttpExecutor result = executor(token);
        return registry == null ? result : new MeteredInternalHttpExecutor(result, registry);
    }

    /**
     * Serializa el DTO antes del envío y transforma fallos Jackson a un diagnóstico seguro.
     *
     * @param value Objeto o texto que se serializa o valida antes de construir la petición.
     * @return representación JSON del valor.
     * @throws es.ubu.batchdownloader.common.ServiceUnavailableException si no se puede serializar
     *     la petición.
     */
    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException exception) {
            throw failure("scraper_request_serialization_failed");
        }
    }

    /**
     * Conserva los estados funcionales 400, 404, 409 y 422; transforma fallos de autenticación
     * interna y los demás estados en indisponibilidad.
     *
     * @param status Código de estado HTTP devuelto por el servicio interno.
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @param fallbackCode Código seguro que se utiliza si el servicio no proporciona uno admisible.
     * @return excepción pública con mensaje fijo y código filtrado.
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
     * Acepta detail.code solo si contiene de uno a ciento veinte caracteres del alfabeto seguro de
     * códigos; descarta mensajes y cuerpos no válidos.
     *
     * @param body Cuerpo de la operación; su representación y posible ausencia dependen del método.
     * @param fallbackCode Código seguro que se utiliza si el servicio no proporciona uno admisible.
     * @return código remoto admisible o código de reserva.
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
     * Construye una indisponibilidad con mensaje fijo para evitar que detalles remotos se propaguen
     * al usuario.
     *
     * @param code Código estable de diagnóstico que puede comunicarse sin copiar el cuerpo remoto.
     * @return error público con el código seguro indicado.
     */
    private ServiceUnavailableException failure(String code) {
        return new ServiceUnavailableException(
                code,
                "No se pudo completar la operación interna del scraper.");
    }

    /**
     * Distingue contenido coincidente, trabajos nuevos y trabajo ya activo en la admisión masiva de
     * descripciones.
     *
     * @param matched Número de aplicaciones que cumplían los criterios de contenido pendiente.
     * @param enqueued Número de trabajos de descripción recién encolados.
     * @param alreadyActive Número de coincidencias que ya tenían un trabajo activo.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Comunicación administrativa
     */
    public record ContentEnqueueResult(int matched, int enqueued, int alreadyActive) {}

    /**
     * Identifica el trabajo asíncrono admitido para generar una descripción.
     *
     * @param jobId UUID textual del trabajo persistente de generación de descripción.
     * @param status Estado persistido del trabajo recién admitido por el scraper.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Comunicación administrativa
     */
    public record DescriptionGeneration(String jobId, String status) {}

    /**
     * Transporta únicamente la aplicación objetivo de una solicitud de generación de descripción.
     *
     * @param appId UUID de la aplicación a consultar, inspeccionar o publicar.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Comunicación administrativa
     */
    private record GenerateDescriptionRequest(String appId) {}
}
