package es.ubu.batchdownloader.downloadworker.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.SourceResolverProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code HttpSourceReferenceResolverTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class HttpSourceReferenceResolverTest {

    /**
     * Comprueba el escenario {@code treatsResolverServerErrorsAsAnItemFailure}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
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
