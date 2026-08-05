package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.application.TemporaryDiskCapacity;
import es.ubu.batchdownloader.downloadworker.config.CoreApiProperties;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Expone a Core una comprobación interna y rápida de la reserva del SSD. */
@RestController
@RequestMapping("/internal/v1/capacity")
final class WorkerCapacityController {
    /** Reserva global del espacio temporal en vuelo. */
    private final TemporaryDiskCapacity capacity;
    /** Directorio real utilizado por las descargas. */
    private final Path temporaryDirectory;
    /** Credencial compartida de servicios internos. */
    private final String serviceToken;

    /** Inicializa la comprobación de capacidad. */
    WorkerCapacityController(
            TemporaryDiskCapacity capacity,
            DownloadProperties downloadProperties,
            CoreApiProperties coreApiProperties) {
        this.capacity = capacity;
        this.temporaryDirectory = Path.of(downloadProperties.tempDirectory());
        this.serviceToken = coreApiProperties.serviceToken();
    }

    /**
     * Rechaza una nueva admisión si el margen de 10 GB o las reservas activas no caben.
     *
     * @param providedToken Credencial enviada por Core.
     * @return Respuesta interna sin cuerpo o error temporal estable.
     */
    @PostMapping("/check")
    ResponseEntity<?> check(
            @RequestHeader(value = "X-Internal-Service-Token", required = false)
                    String providedToken) {
        if (!validToken(providedToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "invalid_internal_token"));
        }
        try {
            capacity.requireAvailable(temporaryDirectory);
            return ResponseEntity.noContent().build();
        } catch (InfrastructureException exception) {
            if (!"storage_busy".equals(exception.getMessage())) {
                throw exception;
            }
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(Map.of(
                            "code", "storage_busy",
                            "message", "No hay capacidad temporal suficiente para iniciar otro ZIP."));
        }
    }

    /** Compara la credencial sin filtraciones temporales triviales. */
    private boolean validToken(String providedToken) {
        return MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8),
                (providedToken == null ? "" : providedToken)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
