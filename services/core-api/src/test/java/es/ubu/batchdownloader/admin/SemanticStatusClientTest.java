package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.common.ConflictException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SemanticStatusClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void forwardsServiceTokenAndReadsHealthStatus() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/semantic/health", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            path.set(exchange.getRequestURI().getPath());
            byte[] body = "{\"status\":\"ok\",\"searchReady\":true}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        SemanticStatusClient.Result result = client().get();

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().path("searchReady").asBoolean()).isTrue();
        assertThat(token).hasValue("internal-secret");
        assertThat(path).hasValue("/semantic/health");
    }

    @Test
    void sanitizesInternalAuthorizationFailure() {
        server = createServer(401);
        SemanticStatusClient semanticClient = client();

        assertThatThrownBy(semanticClient::get)
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code())
                .isEqualTo("semantic_status_internal_unauthorized");
    }

    private HttpServer createServer(int status) {
        try {
            HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            value.createContext("/", exchange -> {
                byte[] body = "{}".getBytes();
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            value.start();
            server = value;
            return value;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private SemanticStatusClient client() {
        return new SemanticStatusClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "internal-secret",
                Duration.ofSeconds(2));
    }
}
