package es.ubu.batchdownloader.downloadworker.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.SourceResolverProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Comprueba la clasificación de indisponibilidad HTTP del scraper durante la resolución de una
 * fuente.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.source.HttpSourceReferenceResolver
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de integración y mensajería
 */
class HttpSourceReferenceResolverTest {

    /** Conserva el motivo de proveedor o validación manteniendo el rechazo individual. */
    @ParameterizedTest
    @CsvSource({
        "503,http_618,source_revalidation_http_618",
        "503,http_429,source_revalidation_http_429",
        "409,http_404,source_revalidation_http_404",
        "503,timeout,source_revalidation_timeout",
        "503,network_error,source_revalidation_network_error",
        "503,source_revalidation_changed,source_revalidation_changed",
        "409,source_not_https,source_not_https",
        "409,installer_signature_mismatch,source_revalidation_installer_signature_mismatch"
    })
    void preservesSafeCauses(int status, String reason, String expected) throws Exception {
        rejects(status, "{\"detail\":{\"reasonCode\":\"" + reason + "\"}}", expected);
    }

    /** Las respuestas antiguas o no fiables nunca se convierten en texto público. */
    @ParameterizedTest
    @ValueSource(strings = {
        "", "not-json", "null", "{}", "{\"detail\":\"private\"}",
        "{\"detail\":{\"code\":\"source_revalidation_transient\"}}",
        "{\"detail\":{\"reasonCode\":\"https://private.test?sig=secret\"}}",
        "{\"detail\":{\"reasonCode\":\"http_618_extra\"}}",
        "{\"detail\":{\"reasonCode\":\"http_099\"}}",
        "{\"detail\":{\"reasonCode\":618}}"
    })
    void keepsCompatibleFallbacks(String body) throws Exception {
        rejects(503, body, "source_resolver_unavailable");
        rejects(409, body, "source_not_verified");
    }

    @Test
    void ignoresOversizedErrorBodies() throws Exception {
        rejects(503, " ".repeat(8193), "source_resolver_unavailable");
    }

    private void rejects(int status, String body, String expected) throws Exception {
        UUID sourceRef = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/sources/" + sourceRef + "/resolution", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            var resolver = new HttpSourceReferenceResolver(HttpClient.newHttpClient(), new ObjectMapper(),
                    new SourceResolverProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-token", Duration.ofSeconds(2)));
            var item = new DownloadItemRequest(UUID.randomUUID(), UUID.randomUUID(), sourceRef);
            assertThatThrownBy(() -> resolver.resolve(item))
                    .isInstanceOf(DownloadRejectedException.class).hasMessage(expected);
        } finally {
            server.stop(0);
        }
    }

    /**
     * Responde 503 desde la ruta de resolución local y exige un rechazo del elemento con
     * source_resolver_unavailable.
     */
    @Test
    void treatsResolverServerErrorsAsAnItemFailure() throws Exception {
        UUID sourceRef = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/sources/" + sourceRef + "/resolution", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpSourceReferenceResolver resolver = new HttpSourceReferenceResolver(
                    HttpClient.newHttpClient(),
                    new ObjectMapper(),
                    new SourceResolverProperties(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-token",
                            Duration.ofSeconds(2)));
            DownloadItemRequest item = new DownloadItemRequest(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    sourceRef);

            assertThatThrownBy(() -> resolver.resolve(item))
                    .isInstanceOf(DownloadRejectedException.class)
                    .hasMessage("source_resolver_unavailable");
        } finally {
            server.stop(0);
        }
    }
}
