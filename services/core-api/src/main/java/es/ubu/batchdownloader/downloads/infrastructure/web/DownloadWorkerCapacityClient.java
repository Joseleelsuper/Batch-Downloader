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

/**
 * Comprueba por HTTP autenticado si el worker puede admitir un nuevo ZIP y convierte fallos
 * temporales en respuestas reintentables de Core.
 *
 * @see es.ubu.batchdownloader.downloads.infrastructure.web.DownloadJobController
 * @see es.ubu.batchdownloader.common.ServiceUnavailableException
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Component
final class DownloadWorkerCapacityClient {
    /**
     * Ejecutor autenticado con tiempo máximo.
     */
    private final InternalHttpExecutor executor;
    /** Endpoint interno de admisión. */
    private final URI endpoint;
    /**
     * Configura la ruta de capacidad y la cadena de transporte, autenticación, plazo y métricas
     * cuando están disponibles.
     *
     * @param workerUrl Origen HTTP interno del worker al que se añade la ruta de comprobación de
     *     capacidad.
     * @param timeout Plazo máximo de conexión y ejecución de la comprobación interna.
     * @param serviceToken Credencial enviada en la cabecera de autenticación entre servicios.
     * @param registry Registro opcional de métricas; null desactiva la instrumentación.
     */
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

    /**
     * Configura la ruta de capacidad y la cadena de transporte, autenticación, plazo y métricas
     * cuando están disponibles.
     *
     * @param httpClient Transporte HTTP inyectable para verificar respuestas y fallos de red.
     * @param endpoint Dirección del almacén alcanzable desde Core.
     * @param timeout Plazo máximo de conexión y ejecución de la comprobación interna.
     * @param serviceToken Credencial enviada en la cabecera de autenticación entre servicios.
     */
    DownloadWorkerCapacityClient(
            HttpClient httpClient,
            URI endpoint,
            Duration timeout,
            String serviceToken) {
        this(endpoint, executor(httpClient, serviceToken, timeout));
    }

    /**
     * Configura la ruta de capacidad y la cadena de transporte, autenticación, plazo y métricas
     * cuando están disponibles.
     *
     * @param endpoint Dirección del almacén alcanzable desde Core.
     * @param executor Cadena de transporte, autenticación y plazo de ejecución de la petición.
     */
    private DownloadWorkerCapacityClient(
            URI endpoint,
            InternalHttpExecutor executor) {
        this.executor = executor;
        this.endpoint = endpoint;
    }

    /**
     * Acepta cualquier respuesta 2xx; convierte 503 en falta temporal de almacenamiento y los demás
     * errores o fallos de transporte en indisponibilidad.
     *
     * @throws es.ubu.batchdownloader.common.ServiceUnavailableException si el worker no confirma
     *     capacidad; incluye un plazo seguro de reintento.
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

    /**
     * Compone transporte JDK, cabecera de servicio y plazo de ejecución de cada petición.
     *
     * @param client Transporte JDK que ejecuta la petición HTTP interna.
     * @param token Credencial interna que se añade a las peticiones al worker.
     * @param timeout Plazo máximo de conexión y ejecución de la comprobación interna.
     * @return ejecutor autenticado con tiempo máximo.
     */
    private static InternalHttpExecutor executor(
            HttpClient client,
            String token,
            Duration timeout) {
        InternalHttpExecutor result = new JdkInternalHttpExecutor(client);
        result = new ServiceTokenInternalHttpExecutor(result, token);
        return new TimeoutInternalHttpExecutor(result, timeout);
    }

    /**
     * Crea el cliente con plazo de conexión y añade métricas a la cadena cuando hay registro.
     *
     * @param token Credencial de autenticación interna del worker.
     * @param timeout Plazo máximo de conexión y ejecución de la comprobación interna.
     * @param registry Registro opcional de métricas; null desactiva la instrumentación.
     * @return ejecutor configurado e instrumentado opcionalmente.
     */
    private static InternalHttpExecutor instrumentedExecutor(
            String token,
            Duration timeout,
            MeterRegistry registry) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        InternalHttpExecutor result = executor(client, token, timeout);
        return registry == null ? result : new MeteredInternalHttpExecutor(result, registry);
    }

    /**
     * Interpreta Retry-After como segundos enteros, limita el resultado a 1–300 y usa un segundo
     * ante ausencia o formato inválido.
     *
     * @param response Respuesta interna cuyo Retry-After determina el siguiente intento.
     * @return segundos seguros que Core comunica para reintentar.
     */
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

    /**
     * Representa una comprobación de capacidad que no pudo completarse con un error seguro y
     * reintento en un segundo.
     *
     * @return excepción temporal service_busy.
     */
    private static ServiceUnavailableException unavailable() {
        return new ServiceUnavailableException(
                "service_busy", "El servicio de descargas no está disponible temporalmente.", 1);
    }
}
