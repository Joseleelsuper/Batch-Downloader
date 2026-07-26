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

class SemanticSearchClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

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

    @Test
    void authorizationFailureDegradesTheWholeRequestToLexical() throws Exception {
        startServer(401, "{}");

        SemanticCandidateSet result = client(Duration.ofSeconds(1))
                .resolve(CatalogSearchMode.SEMANTIC, "editor");

        assertThat(result.appliedMode()).isEqualTo(CatalogSearchMode.LEXICAL);
        assertThat(result.degradedReason()).isEqualTo("semantic_unauthorized");
        assertThat(result.modelVersion()).isNull();
    }

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

    private SemanticSearchClient client(Duration timeout) {
        return new SemanticSearchClient(
                HttpClient.newBuilder().connectTimeout(timeout).build(),
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "internal-token",
                timeout,
                true);
    }

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
