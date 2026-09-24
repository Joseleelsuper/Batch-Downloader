package es.ubu.batchdownloader.downloads.infrastructure.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.downloads.application.port.DownloadStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Consultas de inventario y limpieza fuera de cualquier transacción SQL. */
@Component
public class DownloadStorageGateway implements DownloadStorage {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final String worker;
    private final String scraper;
    private final String token;

    public DownloadStorageGateway(ObjectMapper json,
            @Value("${app.download.worker-capacity-url}") String worker,
            @Value("${app.scraper-api-url}") String scraper,
            @Value("${app.scraper-internal-service-token}") String token) {
        this.json = json;
        this.worker = worker.replaceAll("/+$", "");
        this.scraper = scraper.replaceAll("/+$", "");
        this.token = token;
    }

    public Inventory inventory() {
        try {
            var response = send(worker + "/internal/v1/storage/inventory", "GET", Duration.ofSeconds(30));
            if (response.statusCode() != 200) throw new IllegalStateException("storage_inventory_unavailable");
            return json.readValue(response.body(), Inventory.class);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("invalid_storage_inventory", exception);
        }
    }

    public void delete(UUID jobId) {
        var response = send(worker + "/internal/v1/jobs/" + jobId + "/files", "DELETE", Duration.ofMinutes(2));
        if (response.statusCode() != 204) throw new IllegalStateException("download_cleanup_pending");
    }

    /** Solo recupera metadatos mediante el resolver ya autorizado; no descarga el instalador. */
    public Long revalidateSize(UUID sourceRef) {
        try {
            var response = send(scraper + "/internal/v1/sources/" + sourceRef + "/size", "GET", Duration.ofSeconds(20));
            if (response.statusCode() != 200) return null;
            var body = json.readTree(response.body());
            if (!sourceRef.toString().equals(body.path("sourceRef").asText())) return null;
            long bytes = body.path("expectedSizeBytes").asLong(0);
            return bytes > 0 ? bytes : null;
        } catch (RuntimeException | java.io.IOException exception) {
            return null; // La estimación por mediana cubre proveedores sin metadatos disponibles.
        }
    }

    private HttpResponse<String> send(String url, String method, Duration timeout) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("X-Internal-Service-Token", token)
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("storage_request_interrupted", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("storage_request_failed", exception);
        }
    }
}
