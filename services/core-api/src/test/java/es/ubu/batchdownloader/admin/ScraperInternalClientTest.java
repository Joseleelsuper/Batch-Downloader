package es.ubu.batchdownloader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.admin.AdminDtos.ManualInstallerInspectionRequest;
import es.ubu.batchdownloader.common.NotFoundException;
import es.ubu.batchdownloader.common.UnprocessableEntityException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScraperInternalClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void manualInspectionUsesInternalAuthenticationAndPropagatesTypedSafeErrors()
            throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/internal/v1/admin/apps/00000000-0000-0000-0000-000000000001/"
                        + "manual-installer-inspections",
                exchange -> {
                    token.set(exchange.getRequestHeaders().getFirst(
                            "X-Internal-Service-Token"));
                    body.set(new String(
                            exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8));
                    byte[] response = """
                            {"detail":{"code":"installer_signature_mismatch"}}
                            """.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(422, response.length);
                    exchange.getResponseBody().write(response);
                    exchange.close();
                });
        server.start();

        String installerUrl = "https://downloads.example.test/App.exe?token=secret";
        assertThatThrownBy(() -> client().createManualInstallerInspection(
                        "00000000-0000-0000-0000-000000000001",
                        new ManualInstallerInspectionRequest(
                                installerUrl,
                                "https://example.test/download")))
                .isInstanceOf(UnprocessableEntityException.class)
                .satisfies(exception -> {
                    UnprocessableEntityException typed =
                            (UnprocessableEntityException) exception;
                    assertThat(typed.code()).isEqualTo("installer_signature_mismatch");
                    assertThat(typed.getMessage()).doesNotContain(installerUrl, "secret");
                });
        assertThat(token).hasValue("internal-secret");
        assertThat(body.get()).contains("installerUrl", installerUrl);
    }

    @Test
    void descriptionGenerationUsesTheAuthenticatedInternalRoute() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/v1/content/descriptions/generate", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Internal-Service-Token"));
            byte[] response = """
                    {"jobId":"00000000-0000-0000-0000-000000000009","status":"queued"}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(202, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        ScraperInternalClient.DescriptionGeneration result =
                client().generateDescription("00000000-0000-0000-0000-000000000001");

        assertThat(result.status()).isEqualTo("queued");
        assertThat(token).hasValue("internal-secret");
    }

    @Test
    void currentInspectionPreservesNotFoundAsATypedBoundaryError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = """
                    {"detail":{"code":"inspection_not_found"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> client().currentManualInstallerInspection(
                        "00000000-0000-0000-0000-000000000001"))
                .isInstanceOf(NotFoundException.class)
                .extracting(exception -> ((NotFoundException) exception).code())
                .isEqualTo("inspection_not_found");
    }

    @Test
    void rejectsUnsafeUpstreamErrorCodesInsteadOfEchoingThem() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] response = """
                    {"detail":{"code":"https://example.test/?token=secret"}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(422, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        assertThatThrownBy(() -> client().createManualInstallerInspection(
                        "00000000-0000-0000-0000-000000000001",
                        new ManualInstallerInspectionRequest(
                                "https://downloads.example.test/App.exe",
                                "https://example.test/download")))
                .isInstanceOf(UnprocessableEntityException.class)
                .extracting(exception -> ((UnprocessableEntityException) exception).code())
                .isEqualTo("manual_installer_inspection_failed");
    }

    private ScraperInternalClient client() {
        return new ScraperInternalClient(
                new ObjectMapper().findAndRegisterModules(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "internal-secret");
    }
}
