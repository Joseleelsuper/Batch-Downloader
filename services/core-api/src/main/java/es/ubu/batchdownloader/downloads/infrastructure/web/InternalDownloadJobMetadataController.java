package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.downloads.application.DownloadJobService;
import es.ubu.batchdownloader.downloads.application.DownloadJobService.DownloadItemMetadata;
import es.ubu.batchdownloader.common.UnauthorizedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expone las operaciones HTTP gestionadas por {@code InternalDownloadJobMetadataController}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @apiNote Expone operaciones HTTP sin modificar los contratos de dominio.
 */
@RestController
@RequestMapping("/internal/v1/download-jobs")
public class InternalDownloadJobMetadataController {
    /**
     * Estado {@code jobs} mantenido por {@code InternalDownloadJobMetadataController}.
     */
    private final DownloadJobService jobs;
    /**
     * Estado {@code expectedToken} mantenido por {@code InternalDownloadJobMetadataController}.
     */
    private final byte[] expectedToken;

    /**
     * Inicializa una instancia de {@code InternalDownloadJobMetadataController}.
     *
     * @param jobs Valor de {@code jobs} utilizado por la operación.
     * @param internalServiceToken Valor de {@code internalServiceToken} utilizado por la operación.
     */
    public InternalDownloadJobMetadataController(
            DownloadJobService jobs,
            @Value("${app.scraper-internal-service-token}") String internalServiceToken) {
        this.jobs = jobs;
        this.expectedToken = internalServiceToken.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Ejecuta la operación {@code itemMetadata}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @param request Solicitud recibida por la operación.
     * @param suppliedToken Valor de {@code suppliedToken} utilizado por la operación.
     * @return Colección de elementos obtenidos por la operación.
     */
    @PostMapping("/{jobId}/item-metadata")
    List<DownloadItemMetadata> itemMetadata(
            @PathVariable UUID jobId,
            @Valid @RequestBody DownloadItemMetadataRequest request,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String suppliedToken) {
        requireInternalToken(suppliedToken);
        return jobs.itemMetadata(jobId, request.itemIds());
    }

    /**
     * Ejecuta la operación {@code requireInternalToken}.
     *
     * @param suppliedToken Valor de {@code suppliedToken} utilizado por la operación.
     * @throws UnauthorizedException Si no puede completarse la operación bajo las condiciones
     *     requeridas.
     */
    private void requireInternalToken(String suppliedToken) {
        byte[] supplied = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (suppliedToken == null || !MessageDigest.isEqual(expectedToken, supplied)) {
            throw new UnauthorizedException(
                    "internal_service_token_invalid",
                    "La credencial interna no es válida.");
        }
    }

    /**
     * Representa los datos inmutables de {@code DownloadItemMetadataRequest}.
     *
     * @param itemIds Valor de {@code itemIds} incluido en el record.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     */
    record DownloadItemMetadataRequest(
            @NotEmpty @Size(max = 100) List<@NotNull UUID> itemIds) {}

}
