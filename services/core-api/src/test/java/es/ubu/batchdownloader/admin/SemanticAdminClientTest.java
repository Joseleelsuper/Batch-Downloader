package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.common.ConflictException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Agrupa los escenarios de prueba de {@code SemanticAdminClientTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
class SemanticAdminClientTest {
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
     * Comprueba el escenario {@code
     * forwardsInternalAuthenticationActorAndIdempotencyWithoutExposingThem}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void forwardsInternalAuthenticationActorAndIdempotencyWithoutExposingThem() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> actor = new AtomicReference<>();
        AtomicReference<String> idempotency = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/admin/semantic/benchmarks", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            actor.set(exchange.getRequestHeaders().getFirst("X-Admin-Actor"));
            idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                    {"operationId":"00000000-0000-0000-0000-000000000001","status":"queued"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        SemanticAdminClient.Result result = client().post(
                "/internal/v1/admin/semantic/benchmarks",
                new ObjectMapper().readTree("{\"modelIds\":[\"model-a\",\"model-b\"]}"),
                "administrator",
                "semantic-benchmark-test");

        assertThat(result.status()).isEqualTo(202);
        assertThat(result.body().path("status").asText()).isEqualTo("queued");
        assertThat(token).hasValue("internal-secret");
        assertThat(actor).hasValue("administrator");
        assertThat(idempotency).hasValue("semantic-benchmark-test");
        assertThat(body.get()).contains("model-a").doesNotContain("internal-secret");
    }

    /**
     * Comprueba el escenario {@code internalAuthorizationFailureBecomesASanitizedCoreConflict}.
     *
     * @throws Exception Si no puede completarse la operación bajo las condiciones requeridas.
     */
    @Test
    void internalAuthorizationFailureBecomesASanitizedCoreConflict() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> client().get("/internal/v1/admin/semantic/overview"))
                .isInstanceOf(ConflictException.class)
                .extracting(exception -> ((ConflictException) exception).code())
                .isEqualTo("semantic_admin_internal_unauthorized");
    }

    /**
     * Ejecuta la operación {@code client}.
     *
     * @return Resultado producido por {@code client}.
     */
    private SemanticAdminClient client() {
        return new SemanticAdminClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "internal-secret",
                Duration.ofSeconds(2));
    }
}
