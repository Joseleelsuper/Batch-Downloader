package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.common.ServiceUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Comprueba la capacidad temporal del único worker antes de persistir un trabajo. */
@Component
final class DownloadWorkerCapacityClient {
    /** Cliente HTTP con conexiones reutilizables. */
    private final HttpClient httpClient;
    /** Endpoint interno de admisión. */
    private final URI endpoint;
    /** Credencial compartida entre servicios. */
    private final String serviceToken;
    /** Tiempo total máximo de la comprobación. */
    private final Duration timeout;

    /** Inicializa el cliente interno con esperas acotadas. */
    DownloadWorkerCapacityClient(
            @Value("${app.download.worker-capacity-url}") String workerUrl,
            @Value("${app.download.worker-capacity-timeout}") Duration timeout,
            @Value("${app.scraper-internal-service-token}") String serviceToken) {
        this(
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                URI.create(workerUrl.replaceAll("/+$", "") + "/internal/v1/capacity/check"),
                timeout,
                serviceToken);
    }

    /** Constructor verificable sin red real. */
    DownloadWorkerCapacityClient(
            HttpClient httpClient,
            URI endpoint,
            Duration timeout,
            String serviceToken) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.serviceToken = serviceToken;
    }

    /**
     * Requiere margen temporal antes de entrar en la transacción de creación.
     */
    void requireAvailable() {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("X-Internal-Service-Token", serviceToken)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<Void> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return;
            }
            if (response.statusCode() == 503) {
                throw new ServiceUnavailableException(
                        "storage_busy",
                        "No hay capacidad temporal suficiente para iniciar otro ZIP.",
                        retryAfter(response));
            }
            throw unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException exception) {
            throw unavailable();
        }
    }

    /** Obtiene un Retry-After entero y acotado. */
    private static int retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        return java.util.Optional.of(Integer.parseInt(value));
                    } catch (NumberFormatException ignored) {
                        return java.util.Optional.empty();
                    }
                })
                .map(value -> Math.clamp(value, 1, 300))
                .orElse(1);
    }

    /** Construye el fallo de comunicación sin exponer datos internos. */
    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException(
                "service_busy", "El servicio de descargas no está disponible temporalmente.", 1);
    }
}
