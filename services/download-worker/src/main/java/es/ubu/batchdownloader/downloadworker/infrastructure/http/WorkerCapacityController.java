package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.application.CapacityDeferredException;
import es.ubu.batchdownloader.downloadworker.application.ArtifactCapacity;
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
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Expone a Core una comprobación autenticada de espacio temporal y cuota de objetos para evitar
 * admitir trabajos sin una reserva segura.
 *
 * @see es.ubu.batchdownloader.downloadworker.application.TemporaryDiskCapacity
 * @see es.ubu.batchdownloader.downloadworker.application.ArtifactCapacity
 * @since 0.1.0
 * @version 0.1.0
 * @category Transporte de descargas
 */
@RestController
@RequestMapping("/internal/v1/capacity")
final class WorkerCapacityController {
    /** Reserva global del espacio temporal en vuelo. */
    private final TemporaryDiskCapacity capacity;
    /** Cuota y reservas del bucket de salida. */
    private final ArtifactCapacity artifacts;
    /** Directorio real utilizado por las descargas. */
    private final Path temporaryDirectory;
    /** Credencial compartida de servicios internos. */
    private final String serviceToken;

    /**
     * Conecta ambas comprobaciones de capacidad y la credencial interna usada por Core.
     *
     * @param capacity Comprobación de espacio seguro en el volumen temporal.
     * @param downloadProperties Configuración que determina el directorio temporal supervisado.
     * @param coreApiProperties Configuración que aporta el token de comunicación con Core.
     * @param artifacts Comprobación opcional de cuota de objetos; null se utiliza en la composición
     *     abreviada.
     */
    @Autowired
    WorkerCapacityController(
            TemporaryDiskCapacity capacity,
            DownloadProperties downloadProperties,
            CoreApiProperties coreApiProperties,
            ArtifactCapacity artifacts) {
        this.capacity = capacity;
        this.artifacts = artifacts;
        this.temporaryDirectory = Path.of(downloadProperties.tempDirectory());
        this.serviceToken = coreApiProperties.serviceToken();
    }

    /**
     * Compone una comprobación de disco sin cuota de objetos para pruebas aisladas.
     *
     * @param capacity Comprobación de espacio seguro en el volumen temporal.
     * @param downloadProperties Configuración que determina el directorio temporal supervisado.
     * @param coreApiProperties Configuración que aporta el token de comunicación con Core.
     */
    WorkerCapacityController(
            TemporaryDiskCapacity capacity,
            DownloadProperties downloadProperties,
            CoreApiProperties coreApiProperties) {
        this(capacity, downloadProperties, coreApiProperties, null);
    }

    /**
     * Autentica la petición y comprueba disco y cuota sin mantener una reserva; diferencia
     * credencial inválida de capacidad temporalmente insuficiente.
     *
     * @param providedToken Credencial interna recibida; null o un valor no coincidente impide la
     *     comprobación.
     * @return 204 con margen, 401 con token inválido o 503 con Retry-After de treinta segundos si
     *     falta capacidad.
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
            if (artifacts != null) artifacts.requireAvailable();
            return ResponseEntity.noContent().build();
        } catch (CapacityDeferredException exception) {
            return busy();
        } catch (InfrastructureException exception) {
            if (!"storage_busy".equals(exception.getMessage())) {
                throw exception;
            }
            return busy();
        }
    }

    /**
     * Construye la respuesta de capacidad insuficiente con código y demora estables para Core.
     *
     * @return 503 storage_busy con Retry-After: 30.
     */
    private ResponseEntity<Map<String, String>> busy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "30")
                .body(Map.of(
                        "code", "storage_busy",
                        "message", "No existe una reserva segura de almacenamiento para otro ZIP."));
    }

    /**
     * Rechaza configuración vacía o ausencia de token y compara los bytes UTF-8 con
     * MessageDigest.isEqual.
     *
     * @param providedToken Credencial interna recibida; null o un valor no coincidente impide la
     *     comprobación.
     * @return true si la credencial recibida coincide con la configurada.
     */
    private boolean validToken(String providedToken) {
        if (serviceToken == null || serviceToken.isBlank() || providedToken == null) return false;
        return MessageDigest.isEqual(
                serviceToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8));
    }
}
