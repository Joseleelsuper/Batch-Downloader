package es.ubu.batchdownloader.notification.infrastructure.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import es.ubu.batchdownloader.notification.config.TranslationProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TranslationCatalogClientTest {
    private HttpServer server;
    private AtomicInteger requests;

    @BeforeEach
    void setUp() throws IOException {
        requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/locales/", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void cachesCatalogsByLocale() {
        TranslationCatalogClient client = client(Duration.ofMinutes(5));

        assertThat(client.catalog("es")).containsEntry("email.magicLink.greeting", "Hola.");
        assertThat(client.catalog("ES")).containsEntry("email.magicLink.greeting", "Hola.");

        assertThat(requests).hasValue(1);
    }

    @Test
    void fallsBackToSpanishWhenLocaleIsNotPublished() {
        TranslationCatalogClient client = client(Duration.ofMinutes(5));

        assertThat(client.catalog("fr")).containsEntry("email.magicLink.greeting", "Hola.");
        assertThat(requests).hasValue(2);
    }

    @Test
    void classifiesTranslationServiceFailureAsRetryable() {
        TranslationCatalogClient client = client(Duration.ofMinutes(5));

        assertThatThrownBy(() -> client.catalog("de"))
                .isInstanceOf(RetryableNotificationException.class)
                .hasMessage("translation_temporarily_unavailable");
    }

    private TranslationCatalogClient client(Duration cacheTtl) {
        return new TranslationCatalogClient(
                new TranslationProperties(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Duration.ofSeconds(1), Duration.ofSeconds(1), cacheTtl, "es"),
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-09-21T10:00:00Z"), ZoneOffset.UTC),
                HttpClient.newHttpClient());
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        String locale = exchange.getRequestURI().getPath()
                .substring(exchange.getRequestURI().getPath().lastIndexOf('/') + 1);
        if ("fr".equals(locale)) {
            exchange.sendResponseHeaders(404, -1);
        } else if ("de".equals(locale)) {
            exchange.sendResponseHeaders(503, -1);
        } else {
            byte[] body = "{\"email.magicLink.greeting\":\"Hola.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }
}
