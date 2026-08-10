package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import es.ubu.batchdownloader.notification.config.MailTemplateProperties;
import es.ubu.batchdownloader.notification.config.ResendProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ResendNotificationSenderTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger responseStatus = new AtomicInteger(202);
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final AtomicInteger delayMillis = new AtomicInteger();
    private HttpServer server;
    private ResendNotificationSender sender;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", this::handle);
        server.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        sender = sender(baseUrl, Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsEscapedHtmlAndTextWithStableIdempotency() throws Exception {
        EmailNotification notification = notification("<Ada & friends>", "a token+with/slashes");

        sender.send(notification);

        CapturedRequest request = captured.get();
        assertThat(request.path()).isEqualTo("/emails");
        assertThat(request.authorization()).isEqualTo("Bearer test-resend-key");
        assertThat(request.idempotencyKey()).isEqualTo(notification.eventId().toString());
        JsonNode body = mapper.readTree(request.body());
        assertThat(body.path("from").asText()).isEqualTo("Batch Downloader <no-reply@example.com>");
        assertThat(body.path("to").get(0).asText()).isEqualTo("person@example.com");
        assertThat(body.path("text").asText())
                .contains("<Ada & friends>")
                .contains("/verify-email?token=a%20token%2Bwith%2Fslashes");
        assertThat(body.path("html").asText())
                .contains("&lt;Ada &amp; friends&gt;")
                .doesNotContain("<Ada & friends>");
    }

    @Test
    void classifiesRateLimitsAndServerFailuresAsRetryable() {
        responseStatus.set(429);
        retryAfter.set("17");
        assertThatThrownBy(() -> sender.send(notification("Ada", "token")))
                .isInstanceOfSatisfying(RetryableNotificationException.class,
                        exception -> assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(17)));

        responseStatus.set(503);
        retryAfter.set(null);
        assertThatThrownBy(() -> sender.send(notification("Ada", "token")))
                .isInstanceOfSatisfying(RetryableNotificationException.class,
                        exception -> assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    void classifiesProviderFourHundredsAsPermanent() {
        responseStatus.set(422);
        assertThatThrownBy(() -> sender.send(notification("Ada", "token")))
                .isInstanceOf(PermanentNotificationException.class)
                .hasMessage("resend_request_rejected");
    }

    @Test
    void treatsRequestTimeoutAsRetryableWithoutSendingRealMail() {
        sender = sender(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofMillis(30));
        delayMillis.set(200);

        assertThatThrownBy(() -> sender.send(notification("Ada", "token")))
                .isInstanceOf(RetryableNotificationException.class)
                .hasMessage("resend_io_failure");
    }

    @Test
    void rejectsTamperedEncryptedTokensAsPermanentEvents() {
        assertThatThrownBy(() -> sender.send(notification("Ada", "enc:v1:not-valid")))
                .isInstanceOf(PermanentNotificationException.class)
                .hasMessage("resend_notification_invalid");
        assertThat(captured.get()).isNull();
    }

    @Test
    void missingCredentialsDisableOnlyResendDelivery() {
        ResendNotificationSender disabled = new ResendNotificationSender(
                new ResendProperties(
                        URI.create("https://api.resend.com"), "", "",
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new MailTemplateProperties(
                        "smtp@example.com", "Europe/Madrid", URI.create("https://batch.example.com")),
                new NotificationTokenCipher(KEY), mapper);

        assertThatThrownBy(() -> disabled.send(notification("Ada", "token")))
                .isInstanceOf(PermanentNotificationException.class)
                .hasMessage("resend_not_configured");
    }

    @Test
    void springUsesTheProductionConstructorWhenTheHttpClientTestSeamAlsoExists() {
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ResendProperties properties = new ResendProperties(
                baseUrl, "test-resend-key", "Batch Downloader <no-reply@example.com>",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        MailTemplateProperties mail = new MailTemplateProperties(
                "smtp@example.com", "Europe/Madrid", URI.create("https://batch.example.com"));

        new ApplicationContextRunner()
                .withBean(ResendProperties.class, () -> properties)
                .withBean(MailTemplateProperties.class, () -> mail)
                .withBean(NotificationTokenCipher.class, () -> new NotificationTokenCipher(KEY))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ResendNotificationSender.class)
                .run(context -> assertThat(context).hasSingleBean(ResendNotificationSender.class));
    }

    private ResendNotificationSender sender(URI baseUrl, Duration requestTimeout) {
        return new ResendNotificationSender(
                new ResendProperties(
                        baseUrl, "test-resend-key", "Batch Downloader <no-reply@example.com>",
                        Duration.ofSeconds(1), requestTimeout),
                new MailTemplateProperties(
                        "smtp@example.com", "Europe/Madrid", URI.create("https://batch.example.com")),
                new NotificationTokenCipher(KEY), mapper);
    }

    private EmailNotification notification(String username, String token) {
        return new EmailNotification(
                UUID.randomUUID(), Instant.parse("2026-08-08T10:00:00Z"),
                UUID.randomUUID().toString(), null, "person@example.com",
                EmailNotification.Template.EMAIL_VERIFICATION,
                Map.of("username", username, "token", token));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (delayMillis.get() > 0) Thread.sleep(delayMillis.get());
            captured.set(new CapturedRequest(
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            String header = retryAfter.get();
            if (header != null) exchange.getResponseHeaders().set("Retry-After", header);
            exchange.sendResponseHeaders(responseStatus.get(), -1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private record CapturedRequest(
            String path, String authorization, String idempotencyKey, String body) {}
}
