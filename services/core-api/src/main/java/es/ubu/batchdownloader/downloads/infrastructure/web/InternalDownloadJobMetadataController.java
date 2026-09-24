package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.downloads.application.DownloadJobAccessService;
import es.ubu.batchdownloader.downloads.application.DownloadStorageCoordinator;
import es.ubu.batchdownloader.downloads.application.DownloadJobAccessService.DownloadItemMetadata;
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
 * Entrega al worker metadatos de elementos admitidos mediante una ruta interna protegida con
 * credencial de servicio.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.application.DownloadJobAccessService
 * @see DownloadJobAccessService.DownloadItemMetadata
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@RestController
@RequestMapping("/internal/v1/download-jobs")
public class InternalDownloadJobMetadataController {
    /**
     * Estado {@code jobs} mantenido por {@code InternalDownloadJobMetadataController}.
     */
    private final DownloadJobAccessService jobs;
    /**
     * Estado {@code expectedToken} mantenido por {@code InternalDownloadJobMetadataController}.
     */
    private final byte[] expectedToken;
    private final DownloadStorageCoordinator storage;

    /**
     * Conecta la consulta de metadatos y conserva en UTF-8 la credencial interna esperada.
     *
     * @param jobs Caso de uso que comprueba pertenencia de los elementos al trabajo.
     * @param internalServiceToken Credencial interna esperada, convertida a bytes UTF-8 para su
     *     comparación.
     */
    public InternalDownloadJobMetadataController(
            DownloadJobAccessService jobs,
            DownloadStorageCoordinator storage,
            @Value("${app.scraper-internal-service-token}") String internalServiceToken) {
        this.jobs = jobs;
        this.storage = storage;
        this.expectedToken = internalServiceToken.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Valida la credencial interna antes de resolver los elementos solicitados dentro del trabajo.
     *
     * @param jobId UUID del trabajo de descarga al que pertenecen estado, elementos y ZIP.
     * @param request Entre 1 y 100 UUID de elementos del mismo trabajo.
     * @param suppliedToken Credencial recibida en X-Internal-Service-Token; null se rechaza.
     * @return metadatos en el orden solicitado, sin URLs resueltas ni enlaces firmados.
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
     * Rechaza configuración vacía y credenciales ausentes o distintas mediante comparación de bytes
     * con MessageDigest.isEqual.
     *
     * @param suppliedToken Credencial recibida en X-Internal-Service-Token; null se rechaza.
     * @throws es.ubu.batchdownloader.common.UnauthorizedException si no se ha configurado una
     *     credencial válida o la recibida no coincide.
     */
    private void requireInternalToken(String suppliedToken) {
        byte[] supplied = suppliedToken == null
                ? new byte[0]
                : suppliedToken.getBytes(StandardCharsets.UTF_8);
        if (expectedToken.length == 0 || suppliedToken == null
                || !MessageDigest.isEqual(expectedToken, supplied)) {
            throw new UnauthorizedException(
                    "internal_service_token_invalid",
                    "La credencial interna no es válida.");
        }
    }

    /**
     * Acota la consulta interna a UUID no nulos del mismo trabajo; la aplicación comprueba
     * duplicados y pertenencia.
     *
     * @param itemIds Entre 1 y 100 UUID no nulos de elementos del mismo trabajo, sin duplicados.
     * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
     * @since 0.1.0
     * @version 0.1.0
     * @category Descargas
     */
    record DownloadItemMetadataRequest(
            @NotEmpty @Size(max = 100) List<@NotNull UUID> itemIds) {}

    @PostMapping("/{jobId}/storage")
    DownloadStorageCoordinator.StorageReply storage(
            @PathVariable UUID jobId, @Valid @RequestBody StorageRequest request,
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String suppliedToken) {
        requireInternalToken(suppliedToken);
        return storage.worker(jobId, request.attemptId(), request.action(), request.bytes());
    }

    record StorageRequest(@NotNull UUID attemptId,
            @jakarta.validation.constraints.NotBlank String action,
            @jakarta.validation.constraints.PositiveOrZero long bytes) {}

}
