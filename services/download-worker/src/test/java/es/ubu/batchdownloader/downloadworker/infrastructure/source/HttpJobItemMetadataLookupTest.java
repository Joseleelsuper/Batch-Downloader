package es.ubu.batchdownloader.downloadworker.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.CoreApiProperties;
import es.ubu.batchdownloader.downloadworker.domain.DownloadEvents.DownloadItemRequest;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Comprueba el contrato HTTP interno usado para recuperar nombres y páginas oficiales de los
 * elementos que no pudieron descargarse.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloadworker.infrastructure.source.HttpJobItemMetadataLookup
 * @since 0.1.0
 * @version 0.1.0
 * @category Pruebas de integración y mensajería
 */
class HttpJobItemMetadataLookupTest {
    /**
     * Constante que define {@code JOB_ID}.
     */
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    /**
     * Constante que define {@code ITEM_ID}.
     */
    private static final UUID ITEM_ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    /**
     * Constante que define {@code APP_ID}.
     */
    private static final UUID APP_ID = UUID.fromString("00000000-0000-4000-8000-000000000003");

    /**
     * Consulta un elemento en un servidor local y comprueba secreto interno, cuerpo con su UUID y
     * lectura de nombre y página oficial.
     */
    @Test
    void requestsAllFailedIdsOnceAndValidatesTheExactResponse() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("""
                    [{
                      "itemId":"%s",
                      "appId":"%s",
                      "appName":"Aplicación fallida",
                      "officialPageUrl":"https://example.com/app"
                    }]
                    """.formatted(ITEM_ID, APP_ID)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            var lookup = lookup(server);

            var metadata = lookup.find(
                    JOB_ID,
                    List.of(new DownloadItemRequest(ITEM_ID, APP_ID, UUID.randomUUID())));

            assertThat(metadata.get(ITEM_ID).appName()).isEqualTo("Aplicación fallida");
            assertThat(metadata.get(ITEM_ID).officialPageUrl()).isEqualTo("https://example.com/app");
            assertThat(token.get()).isEqualTo("test-token");
            assertThat(body.get()).isEqualTo("{\"itemIds\":[\"" + ITEM_ID + "\"]}");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Responde 503 desde Core y comprueba que la consulta propaga InfrastructureException con
     * job_metadata_unavailable.
     */
    @Test
    void makesUnavailableCoreResponsesRetriable() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        try {
            var lookup = lookup(server);

            assertThatThrownBy(() -> lookup.find(
                            JOB_ID,
                            List.of(new DownloadItemRequest(ITEM_ID, APP_ID, UUID.randomUUID()))))
                    .isInstanceOf(InfrastructureException.class)
                    .hasMessage("job_metadata_unavailable");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Devuelve una lista vacía para un elemento solicitado y exige invalid_job_metadata_response
     * como fallo de infraestructura.
     */
    @Test
    void rejectsPartialOrMismatchedMetadataResponses() throws Exception {
        HttpServer server = server(exchange -> {
            byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            var lookup = lookup(server);

            assertThatThrownBy(() -> lookup.find(
                            JOB_ID,
                            List.of(new DownloadItemRequest(ITEM_ID, APP_ID, UUID.randomUUID()))))
                    .isInstanceOf(InfrastructureException.class)
                    .hasMessage("invalid_job_metadata_response");
        } finally {
            server.stop(0);
        }
    }

    /**
     * Configura la consulta contra el puerto local del escenario con secreto fijo y timeout de dos
     * segundos.
     *
     * @param server servidor HTTP local con puerto efímero del escenario.
     * @return cliente de metadatos con serializador de fechas y UUID.
     */
    private HttpJobItemMetadataLookup lookup(HttpServer server) {
        return new HttpJobItemMetadataLookup(
                HttpClient.newHttpClient(),
                new ObjectMapper().findAndRegisterModules(),
                new CoreApiProperties(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "test-token",
                        Duration.ofSeconds(2)));
    }

    /**
     * Abre un servidor loopback con puerto efímero y registra la ruta de metadatos del trabajo de
     * prueba.
     *
     * @param handler respuesta simulada para la ruta interna de metadatos.
     * @return servidor iniciado que el escenario debe detener en su bloque finally.
     * @throws java.lang.Exception si no puede abrirse o configurarse el servidor HTTP local.
     */
    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/download-jobs/" + JOB_ID + "/item-metadata",
                handler);
        server.start();
        return server;
    }
}
