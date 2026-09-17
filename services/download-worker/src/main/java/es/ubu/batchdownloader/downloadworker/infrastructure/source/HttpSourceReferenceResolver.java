package es.ubu.batchdownloader.downloadworker.infrastructure.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.SourceResolverProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.ResolvedDownloadItem;
import es.ubu.batchdownloader.downloadworker.domain.DownloadModels.InstallationMetadata;
import es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Consulta al scraper la fuente exacta admitida y contrasta identidad, pertenencia y confianza
 * antes de permitir que el worker use su URI final.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.ports.SourceReferenceResolver
 * @see es.ubu.batchdownloader.downloadworker.application.DownloadResolutionService
 * @since 0.1.0
 * @version 0.1.0
 * @category Adaptadores y persistencia del worker
 */
public class HttpSourceReferenceResolver implements SourceReferenceResolver {
    /**
     * Estado {@code client} mantenido por {@code HttpSourceReferenceResolver}.
     */
    private final HttpClient client;
    /**
     * Dependencia {@code objectMapper} utilizada por {@code HttpSourceReferenceResolver}.
     */
    private final ObjectMapper objectMapper;
    /**
     * Estado {@code properties} mantenido por {@code HttpSourceReferenceResolver}.
     */
    private final SourceResolverProperties properties;
    /**
     * Estado {@code baseUrl} mantenido por {@code HttpSourceReferenceResolver}.
     */
    private final String baseUrl;

    /**
     * Conecta cliente, serializador y configuración del scraper normalizando la URL base.
     *
     * @param client Cliente del servicio remoto, configurado antes de componer el adaptador.
     * @param objectMapper Serializador de contratos JSON entre servicios.
     * @param properties Configuración específica del adaptador: destino, credencial y límites de
     *     acceso.
     */
    public HttpSourceReferenceResolver(
            HttpClient client,
            ObjectMapper objectMapper,
            SourceResolverProperties properties) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.baseUrl = properties.baseUrl().replaceAll("/+$", "");
    }

    /**
     * Solicita la resolución autenticada y comprueba la respuesta. Un 404, 409, 5xx u otro estado
     * no satisfactorio se conserva como rechazo individual para poder entregar el resto del lote.
     *
     * @param item Elemento admitido cuya fuente exacta se resuelve.
     * @return resolución con los UUID originales y metadatos de instalación.
     * @throws es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException si el
     *     scraper rechaza o no puede resolver esa fuente.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si falla el
     *     transporte, JSON o la consistencia del contrato.
     */
    @Override
    public ResolvedDownloadItem resolve(DownloadItemRequest item) {
        URI endpoint = URI.create(baseUrl + "/internal/v1/sources/" + item.sourceRef() + "/resolution");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.timeout())
                .header("Accept", "application/json")
                .header("X-Internal-Service-Token", properties.serviceToken())
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 404) {
            throw new DownloadRejectedException("source_not_found");
        }
        if (response.statusCode() == 409) {
            throw new DownloadRejectedException("source_not_verified");
        }
        if (response.statusCode() >= 500) {
            // La resolución se limita a un instalador. Trata un 5xx remoto como fallo
            // del elemento para poder producir el resto del lote. Relanzarlo como fallo
            // de infraestructura reintentaba antes todo el evento y dejaba activo el
            // trabajo de Core cuando Rabbit agotaba sus reintentos.
            throw new DownloadRejectedException(
                    "source_resolver_unavailable",
                    new IllegalStateException("HTTP " + response.statusCode()));
        }
        if (response.statusCode() != 200) {
            throw new DownloadRejectedException("source_resolution_http_" + response.statusCode());
        }
        SourceResolutionResponse resolved = deserialize(response.body());
        validateResponse(item, resolved);
        return new ResolvedDownloadItem(
                item.itemId(),
                item.appId(),
                item.sourceRef(),
                URI.create(resolved.url()),
                resolved.expectedFilename(),
                resolved.operatingSystem(),
                resolved.architecture(),
                resolved.expectedSizeBytes(),
                normalizeSha256(resolved.expectedSha256()),
                resolved.expectedMime(),
                new InstallationMetadata(resolved.appName(), resolved.version(), resolved.extension(),
                        resolved.operatingSystem(), resolved.architecture(), resolved.installationProfile(),
                        resolved.signatureBase64()));
    }

    /**
     * Envía la petición interna de resolución y conserva la interrupción en caso de cancelación del
     * hilo.
     *
     * @param request Petición interna ya construida con token y timeout.
     * @return respuesta HTTP con cuerpo textual.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si se
     *     interrumpe la petición o falla la E/S.
     */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InfrastructureException("source_resolution_interrupted", exception);
        } catch (IOException exception) {
            throw new InfrastructureException("source_resolver_unavailable", exception);
        }
    }

    /**
     * Lee el contrato de resolución sin utilizar todavía la URI remota.
     *
     * @param body Cuerpo JSON recibido del servicio interno; se valida antes de utilizarlo.
     * @return respuesta deserializada del scraper.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si el JSON
     *     no puede interpretarse.
     */
    private SourceResolutionResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, SourceResolutionResponse.class);
        } catch (IOException exception) {
            throw new InfrastructureException("invalid_source_resolution_response", exception);
        }
    }

    /**
     * Exige fuente y aplicación exactas, confianza VERIFIED, URI y plataforma presentes, tamaño no
     * negativo y SHA-256 válido cuando se proporciona.
     *
     * @param requested Elemento original contra el que se contrastan identidad y pertenencia de la
     *     respuesta.
     * @param resolved Respuesta del scraper que debe conservar la selección y declarar confianza
     *     VERIFIED.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si no se
     *     cumple alguna condición del contrato de resolución.
     */
    private void validateResponse(DownloadItemRequest requested, SourceResolutionResponse resolved) {
        if (resolved.sourceRef() == null
                || resolved.appId() == null
                || !resolved.sourceRef().equals(requested.sourceRef())
                || !resolved.appId().equals(requested.appId())
                || !"VERIFIED".equals(resolved.trustStatus())
                || resolved.url() == null
                || resolved.url().isBlank()
                || resolved.operatingSystem() == null
                || resolved.operatingSystem().isBlank()
                || resolved.architecture() == null
                || resolved.architecture().isBlank()) {
            throw new InfrastructureException(
                    "invalid_source_resolution_response",
                    new IllegalArgumentException("Source resolver returned inconsistent identifiers or trust"));
        }
        if (resolved.expectedSizeBytes() != null && resolved.expectedSizeBytes() < 0) {
            throw new InfrastructureException(
                    "invalid_source_resolution_response",
                    new IllegalArgumentException("Negative expectedSizeBytes"));
        }
        normalizeSha256(resolved.expectedSha256());
    }

    /**
     * Representa una huella ausente como null y exige sesenta y cuatro caracteres hexadecimales
     * para una huella aportada.
     *
     * @param sha256 SHA-256 calculado del contenido descargado, cuando existe un archivo.
     * @return huella en minúsculas o null para ausencia.
     * @throws es.ubu.batchdownloader.downloadworker.application.InfrastructureException si la
     *     huella no cumple el formato SHA-256.
     */
    private String normalizeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        String normalized = sha256.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new InfrastructureException(
                    "invalid_source_resolution_response",
                    new IllegalArgumentException("Invalid expectedSha256"));
        }
        return normalized;
    }

    /**
     * Recibe del scraper una resolución exacta con evidencia de confianza, integridad esperada y
     * datos declarativos de instalación.
     *
     * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
     *     candidato automático.
     * @param appId UUID de la aplicación seleccionada en el catálogo.
     * @param url URI final revalidada que usa el worker; no se incluye en los manifiestos
     *     entregados.
     * @param expectedFilename Nombre propuesto del instalador; la política de nombres lo saneará
     *     antes de escribir.
     * @param expectedSizeBytes Tamaño esperado en bytes; null cuando la inspección no lo conoce.
     * @param expectedSha256 SHA-256 esperado para verificar integridad; null si no se conoce.
     * @param expectedMime Tipo MIME esperado de la fuente, cuando la resolución lo proporciona.
     * @param operatingSystem Plataforma declarada para la fuente concreta.
     * @param architecture Arquitectura declarada para la fuente concreta.
     * @param trustStatus Estado VERIFIED exigido antes de utilizar la resolución.
     * @param appName Nombre de la aplicación que se muestra en el manifiesto o las instrucciones
     *     manuales.
     * @param version Versión del programa correspondiente al instalador, cuando se conoce.
     * @param extension Formato del instalador sin incluir una dirección de descarga.
     * @param installationProfile Receta Linux declarativa de la fuente, cuando está disponible.
     * @param signatureBase64 Firma separada del instalador codificada en Base64, cuando la fuente
     *     la proporciona.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Adaptadores y persistencia del worker
     */
    public record SourceResolutionResponse(
            UUID sourceRef,
            UUID appId,
            String url,
            String expectedFilename,
            Long expectedSizeBytes,
            String expectedSha256,
            String expectedMime,
            String operatingSystem,
            String architecture,
            String trustStatus,
            String appName,
            String version,
            String extension,
            Map<String, Object> installationProfile,
            String signatureBase64) {
        /**
         * Conserva el contrato abreviado de resolución sin nombre, versión, receta ni firma de
         * instalación.
         *
         * @param sourceRef UUID exacto del instalador seleccionado; no debe sustituirse por otro
         *     candidato automático.
         * @param appId UUID de la aplicación seleccionada en el catálogo.
         * @param url URI final revalidada que usa el worker; no se incluye en los manifiestos
         *     entregados.
         * @param expectedFilename Nombre propuesto del instalador; la política de nombres lo
         *     saneará antes de escribir.
         * @param expectedSizeBytes Tamaño esperado en bytes; null cuando la inspección no lo
         *     conoce.
         * @param expectedSha256 SHA-256 esperado para verificar integridad; null si no se conoce.
         * @param expectedMime Tipo MIME esperado de la fuente, cuando la resolución lo proporciona.
         * @param operatingSystem Plataforma declarada para la fuente concreta.
         * @param architecture Arquitectura declarada para la fuente concreta.
         * @param trustStatus Estado VERIFIED exigido antes de utilizar la resolución.
         */
        public SourceResolutionResponse(UUID sourceRef, UUID appId, String url, String expectedFilename,
                Long expectedSizeBytes, String expectedSha256, String expectedMime,
                String operatingSystem, String architecture, String trustStatus) {
            this(sourceRef, appId, url, expectedFilename, expectedSizeBytes, expectedSha256,
                    expectedMime, operatingSystem, architecture, trustStatus, null, null, null, null, null);
        }
    }
}
