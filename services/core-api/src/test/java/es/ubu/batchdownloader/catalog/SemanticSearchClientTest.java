package es.ubu.batchdownloader.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code SemanticSearchClientTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class SemanticSearchClientTest {
    /**
     * Dato compartido {@code server} para los escenarios de prueba.
     */
    private HttpServer server;

    /**
     * Libera el estado utilizado por los escenarios de prueba.
     */
    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * Comprueba el escenario {@code successfulResponseKeepsImmutableModelAndIndexVersions}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void successfulResponseKeepsImmutableModelAndIndexVersions() throws Exception {
        startServer(200, """
                {
                  "candidates": [
                    {
                      "appId": "00000000-0000-0000-0000-000000000001",
                      "rank": 1,
                      "similarity": 0.91
                    }
                  ],
                  "modelVersion": "e5-v1",
                  "indexVersion": "index-v1",
                  "truncated": false
                }
                """);

        SemanticCandidateSet result = client(Duration.ofSeconds(1))
                .resolve(CatalogSearchMode.SEMANTIC, "editor de código");

        assertThat(result.semantic()).isTrue();
        assertThat(result.modelVersion()).isEqualTo("e5-v1");
        assertThat(result.indexVersion()).isEqualTo("index-v1");
        assertThat(result.candidatesJson()).contains("\"rank\":1");
    }

    /**
     * Comprueba el escenario {@code authorizationFailureDegradesTheWholeRequestToLexical}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void authorizationFailureDegradesTheWholeRequestToLexical() throws Exception {
        startServer(401, "{}");

        SemanticCandidateSet result = client(Duration.ofSeconds(1))
                .resolve(CatalogSearchMode.SEMANTIC, "editor");

        assertThat(result.appliedMode()).isEqualTo(CatalogSearchMode.LEXICAL);
        assertThat(result.degradedReason()).isEqualTo("semantic_unauthorized");
        assertThat(result.modelVersion()).isNull();
    }

    /**
     * Comprueba el escenario {@code truncatedEnumerationNeverPublishesAPartialSemanticScope}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void truncatedEnumerationNeverPublishesAPartialSemanticScope() throws Exception {
        startServer(200, """
                {
                  "candidates": [],
                  "modelVersion": "e5-v1",
                  "indexVersion": "index-v1",
                  "truncated": true
                }
                """);

        SemanticCandidateSet result = client(Duration.ofSeconds(1))
                .resolve(CatalogSearchMode.SEMANTIC, "editor");

        assertThat(result.appliedMode()).isEqualTo(CatalogSearchMode.LEXICAL);
        assertThat(result.degradedReason()).isEqualTo("semantic_candidates_truncated");
    }

    /**
     * Ejecuta la operación {@code client}.
     *
     * @param timeout Tiempo máximo permitido para completar la operación.
     * @return Resultado producido por {@code client}.
     */
    private SemanticSearchClient client(Duration timeout) {
        return new SemanticSearchClient(
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "internal-token",
                timeout);
    }

    /**
     * Ejecuta la operación {@code startServer}.
     *
     * @param status Estado utilizado para filtrar o actualizar el recurso.
     * @param body Cuerpo recibido por la solicitud.
     * @throws IOException Si se produce un error al leer o escribir los datos requeridos.
     */
    private void startServer(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/semantic/search", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
}
