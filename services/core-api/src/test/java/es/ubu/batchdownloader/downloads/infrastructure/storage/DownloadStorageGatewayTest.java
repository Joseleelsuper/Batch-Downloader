package es.ubu.batchdownloader.downloads.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.downloads.application.port.DownloadStorage.StoredJob;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verifica el contrato HTTP que confirma espacio ocupado, borrados y tamaños estimados. */
class DownloadStorageGatewayTest {
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SOURCE_REF = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final ConcurrentLinkedQueue<Request> requests = new ConcurrentLinkedQueue<>();
    private HttpServer server;
    private DownloadStorageGateway gateway;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), status.get() == 204 ? -1 : response.length);
            if (status.get() != 204) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        gateway = new DownloadStorageGateway(new ObjectMapper(), base + "/worker///",
                base + "/scraper/", "internal-secret");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void readsWorkerInventoryWithInternalAuthenticationAndLongByteCounts() {
        body.set("""
                {"jobs":[{"jobId":"%s","bytes":4294967296,"active":true},
                         {"jobId":"%s","bytes":123,"active":false}],
                 "availableBytes":10737418240}
                """.formatted(JOB_ID, SOURCE_REF));

        var inventory = gateway.inventory();

        assertThat(inventory.availableBytes()).isEqualTo(10_737_418_240L);
        assertThat(inventory.jobs()).containsExactly(new StoredJob(JOB_ID, 4_294_967_296L, true),
                new StoredJob(SOURCE_REF, 123, false));
        assertThat(requests).containsExactly(new Request("GET", "/worker/internal/v1/storage/inventory",
                "internal-secret", ""));
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 401, 503})
    void refusesInventoryWithoutAnAuthoritativeSuccessfulResponse(int responseStatus) {
        status.set(responseStatus);
        body.set("{\"jobs\":[],\"availableBytes\":10737418240}");

        assertThatThrownBy(gateway::inventory).isInstanceOf(IllegalStateException.class)
                .hasMessage("storage_inventory_unavailable");
    }

    @Test
    void rejectsMalformedInventoryInsteadOfAssumingEmptyStorage() {
        body.set("not-json");

        assertThatThrownBy(gateway::inventory).isInstanceOf(IllegalStateException.class)
                .hasMessage("invalid_storage_inventory").hasCauseInstanceOf(IOException.class);
    }

    @Test
    void deletesOnlyTheRequestedWorkerJobAndWaitsForNoContentConfirmation() {
        status.set(204);

        gateway.delete(JOB_ID);

        assertThat(requests).containsExactly(new Request("DELETE",
                "/worker/internal/v1/jobs/" + JOB_ID + "/files", "internal-secret", ""));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 202, 404, 503})
    void leavesCleanupPendingWithoutExplicitDeletionConfirmation(int responseStatus) {
        status.set(responseStatus);

        assertThatThrownBy(() -> gateway.delete(JOB_ID)).isInstanceOf(IllegalStateException.class)
                .hasMessage("download_cleanup_pending");
    }

    @Test
    void revalidatesOnlySourceMetadataUsingTheScraperAndInternalAuthentication() {
        body.set("{\"sourceRef\":\"" + SOURCE_REF + "\",\"expectedSizeBytes\":4294967296}");

        assertThat(gateway.revalidateSize(SOURCE_REF)).isEqualTo(4_294_967_296L);
        assertThat(requests).containsExactly(new Request("GET",
                "/scraper/internal/v1/sources/" + SOURCE_REF + "/size", "internal-secret", ""));
    }

    @Test
    void ignoresMetadataForAnotherSource() {
        body.set("{\"sourceRef\":\"" + JOB_ID + "\",\"expectedSizeBytes\":123}");

        assertThat(gateway.revalidateSize(SOURCE_REF)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null", "\"unknown\"", "{}"})
    void fallsBackToEstimationWhenSizeIsUnavailable(String size) {
        body.set("{\"sourceRef\":\"" + SOURCE_REF + "\",\"expectedSizeBytes\":" + size + "}");

        assertThat(gateway.revalidateSize(SOURCE_REF)).isNull();
    }

    @Test
    void fallsBackToEstimationWhenMetadataHasNoSize() {
        body.set("{\"sourceRef\":\"" + SOURCE_REF + "\"}");

        assertThat(gateway.revalidateSize(SOURCE_REF)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "null", "{}", "{\"expectedSizeBytes\":123}"})
    void fallsBackToEstimationForMalformedOrUnidentifiedMetadata(String response) {
        body.set(response);

        assertThat(gateway.revalidateSize(SOURCE_REF)).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 404, 503})
    void ignoresSizeFromAnUnsuccessfulResponse(int responseStatus) {
        status.set(responseStatus);
        body.set("{\"sourceRef\":\"" + SOURCE_REF + "\",\"expectedSizeBytes\":123}");

        assertThat(gateway.revalidateSize(SOURCE_REF)).isNull();
    }

    @Test
    void connectionFailureBlocksStorageChangesButAllowsMetadataEstimation() {
        server.stop(0);

        assertThatThrownBy(gateway::inventory).isInstanceOf(IllegalStateException.class)
                .hasMessage("storage_request_failed").hasCauseInstanceOf(IOException.class);
        assertThatThrownBy(() -> gateway.delete(JOB_ID)).isInstanceOf(IllegalStateException.class)
                .hasMessage("storage_request_failed").hasCauseInstanceOf(IOException.class);
        assertThat(gateway.revalidateSize(SOURCE_REF)).isNull();
    }

    @Test
    void preservesInterruptionInsteadOfTreatingAnUnfinishedRequestAsSuccess() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(gateway::inventory).isInstanceOf(IllegalStateException.class)
                    .hasMessage("storage_request_interrupted").hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private record Request(String method, String path, String token, String body) {}
}
