package es.ubu.batchdownloader.downloadworker.infrastructure.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.downloadworker.application.InfrastructureException;
import es.ubu.batchdownloader.downloadworker.config.CoreApiProperties;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpJobStorageLedgerTest {
    @Test
    void sendsAuthenticatedFencedReservationAndRejectsUnavailableOrInvalidReplies() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        AtomicInteger status = new AtomicInteger(200);
        AtomicReference<String> reply = new AtomicReference<>(
                "{\"allowed\":true,\"reservedBytes\":100,\"cancelled\":false,\"budgetBytes\":1000}");
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/download-jobs/" + jobId + "/storage", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            byte[] response = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (HttpClient client = HttpClient.newHttpClient()) {
            ObjectMapper mapper = new ObjectMapper();
            HttpJobStorageLedger ledger = new HttpJobStorageLedger(client, mapper,
                    new CoreApiProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-token", Duration.ofSeconds(2)));
            assertThat(ledger.update(jobId, attemptId, "RESERVE", 100).reservedBytes()).isEqualTo(100);
            assertThat(token).hasValue("test-token");
            assertThat(mapper.readTree(body.get()).path("attemptId").asText()).isEqualTo(attemptId.toString());
            assertThat(mapper.readTree(body.get()).path("action").asText()).isEqualTo("RESERVE");
            status.set(503);
            assertThatThrownBy(() -> ledger.update(jobId, attemptId, "RESERVE", 100))
                    .isInstanceOf(InfrastructureException.class);
            status.set(200);
            reply.set("{\"allowed\":true,\"reservedBytes\":1001,\"budgetBytes\":1000}");
            assertThatThrownBy(() -> ledger.update(jobId, attemptId, "RESERVE", 100))
                    .isInstanceOf(InfrastructureException.class);
        } finally {
            server.stop(0);
        }
    }
}
