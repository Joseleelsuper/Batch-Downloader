package es.ubu.batchdownloader.downloads.infrastructure.web;

import es.ubu.batchdownloader.common.ServiceUnavailableException;
import es.ubu.batchdownloader.common.http.InternalHttpExecutor;
import es.ubu.batchdownloader.common.http.InternalHttpRequest;
import es.ubu.batchdownloader.common.http.InternalHttpResponse;
import es.ubu.batchdownloader.common.http.InternalHttpTransportException;
import es.ubu.batchdownloader.common.http.JdkInternalHttpExecutor;
import es.ubu.batchdownloader.common.http.MeteredInternalHttpExecutor;
import es.ubu.batchdownloader.common.http.ServiceTokenInternalHttpExecutor;
import es.ubu.batchdownloader.common.http.TimeoutInternalHttpExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/** Comprueba la capacidad temporal del único worker antes de persistir un trabajo. */
@Component
final class DownloadWorkerCapacityClient {
    /** Ejecutor HTTP interno con políticas transversales compuestas. */
    private final InternalHttpExecutor executor;
    /** Endpoint interno de admisión. */
    private final URI endpoint;
    /** Inicializa el cliente interno con esperas acotadas. */
    @Autowired
    DownloadWorkerCapacityClient(
            @Value("${app.download.worker-capacity-url}") String workerUrl,
            @Value("${app.download.worker-capacity-timeout}") Duration timeout,
            @Value("${app.scraper-internal-service-token}") String serviceToken,
            @Nullable MeterRegistry registry) {
        this(
                URI.create(workerUrl.replaceAll("/+$", "") + "/internal/v1/capacity/check"),
                instrumentedExecutor(serviceToken, timeout, registry));
    }

    /** Constructor compatible para contextos sin observabilidad. */
    DownloadWorkerCapacityClient(
            String workerUrl,
            Duration timeout,
            String serviceToken) {
        this(
                URI.create(workerUrl.replaceAll("/+$", "") + "/internal/v1/capacity/check"),
                instrumentedExecutor(serviceToken, timeout, null));
    }

    /** Constructor verificable sin red real. */
    DownloadWorkerCapacityClient(
            HttpClient httpClient,
            URI endpoint,
            Duration timeout,
            String serviceToken) {
        this(endpoint, executor(httpClient, serviceToken, timeout));
    }

    private DownloadWorkerCapacityClient(
            URI endpoint,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.endpoint = endpoint;
    }

    /**
     * Requiere margen temporal antes de entrar en la transacción de creación.
     */
    void requireAvailable() {
        InternalHttpRequest request = new InternalHttpRequest(
                "download-worker", "capacity", "POST", endpoint, null);
        try {
            InternalHttpResponse response = executor.execute(request);
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
        } catch (InternalHttpTransportException exception) {
            throw unavailable();
        }
    }

    private static InternalHttpExecutor executor(
            HttpClient client,
            String token,
            Duration timeout) {
        InternalHttpExecutor result = new JdkInternalHttpExecutor(client);
        result = new ServiceTokenInternalHttpExecutor(result, token);
        return new TimeoutInternalHttpExecutor(result, timeout);
    }

    private static InternalHttpExecutor instrumentedExecutor(
            String token,
            Duration timeout,
            MeterRegistry registry) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        InternalHttpExecutor result = executor(client, token, timeout);
        return registry == null ? result : new MeteredInternalHttpExecutor(result, registry);
    }

    /** Obtiene un Retry-After entero y acotado. */
    private static int retryAfter(InternalHttpResponse response) {
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
