package es.ubu.batchdownloader.downloadworker.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.downloadworker.application.DownloadRejectedException;
import es.ubu.batchdownloader.downloadworker.config.DownloadProperties;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class JdkRemoteExchangeTest {
    @Test
    void deadlineClosesTheRealBodyAndUnblocksAStalledRead() throws Exception {
        var release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stalled", request -> {
            request.sendResponseHeaders(200, 2);
            request.getResponseBody().write(1);
            request.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { request.close(); }
        });
        server.start();
        var properties = new DownloadProperties(10, DataSize.ofMegabytes(1), 1,
                Duration.ofSeconds(1), Duration.ofMillis(400), Duration.ofMinutes(1), "unused");
        try (HttpClient client = HttpClient.newHttpClient()) {
            var exchange = new JdkRemoteExchange(client, properties);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                try (var response = exchange.get(URI.create(
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/stalled"))) {
                    assertThatThrownBy(() -> response.body().readAllBytes())
                            .isInstanceOf(DownloadRejectedException.class).hasMessage("remote_timeout");
                }
            });
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
