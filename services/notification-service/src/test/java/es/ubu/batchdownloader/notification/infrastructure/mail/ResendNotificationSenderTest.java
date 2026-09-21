package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import es.ubu.batchdownloader.notification.config.MailTemplateProperties;
import es.ubu.batchdownloader.notification.config.ResendProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import es.ubu.batchdownloader.notification.infrastructure.translation.TranslationCatalogClient;
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
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Comprueba el contrato HTTP de identidad con un servidor local, incluido escape, cifrado,
 * idempotencia y errores.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
class ResendNotificationSenderTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger responseStatus = new AtomicInteger(202);
    private final AtomicReference<String> retryAfter = new AtomicReference<>();
    private final AtomicReference<CapturedRequest> captured = new AtomicReference<>();
    private final AtomicInteger delayMillis = new AtomicInteger();
    private TranslationCatalogClient translations;
    private HttpServer server;
    /**
     * Emisor aislado para probar el intercambio HTTP.
     */
    private ResendNotificationSender sender;

    /**
     * Arranca un servidor HTTP local controlado para capturar peticiones de prueba sin enviar
     * correo real.
     */
    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", this::handle);
        server.start();
        translations = Mockito.mock(TranslationCatalogClient.class);
        Mockito.when(translations.catalog("es")).thenReturn(catalog("Hola."));
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        sender = sender(baseUrl, Duration.ofSeconds(1));
    }

    /**
     * Detiene el servidor local y libera los recursos de la prueba.
     */
    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    /**
     * Comprueba texto y HTML escapado, descifrado del token y clave de idempotencia estable para el
     * mismo evento.
     */
    @Test
    void sendsEscapedHtmlAndTextWithStableIdempotency() throws Exception {
        Mockito.when(translations.catalog("es")).thenReturn(catalog("Hola <Ada & friends>"));
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
                .contains("/login#token=a%20token%2Bwith%2Fslashes");
        assertThat(body.path("html").asText())
                .contains("&lt;Ada &amp; friends&gt;")
                .doesNotContain("<Ada & friends>");
    }

    /**
     * Comprueba que 429 y errores del servidor se clasifican como temporales y conservan la espera
     * del proveedor.
     */
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

    /**
     * Comprueba que los rechazos de petición no temporales provocan un fallo permanente.
     */
    @Test
    void classifiesProviderFourHundredsAsPermanent() {
        responseStatus.set(422);
        assertThatThrownBy(() -> sender.send(notification("Ada", "token")))
                .isInstanceOf(PermanentNotificationException.class)
                .hasMessage("resend_request_rejected");
    }

    /**
     * Comprueba que agotar el tiempo de una petición local produce un fallo reintentable.
     */
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

    /**
     * Comprueba que un sobre cifrado manipulado se rechaza permanentemente antes del envío.
     */
    @Test
    void rejectsTamperedEncryptedTokensAsPermanentEvents() {
        assertThatThrownBy(() -> sender.send(notification("Ada", "enc:v1:not-valid")))
                .isInstanceOf(PermanentNotificationException.class)
                .hasMessage("resend_notification_invalid");
        assertThat(captured.get()).isNull();
    }

    /**
     * Comprueba que la ausencia de credenciales impide el envío HTTP mediante un fallo permanente
     * de configuración.
     */
    @Test
    void missingCredentialsDisableOnlyResendDelivery() {
        ResendNotificationSender disabled = new ResendNotificationSender(
                new ResendProperties(
                        URI.create("https://api.resend.com"), "", "",
                        Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new MailTemplateProperties(URI.create("https://batch.example.com")),
                new NotificationTokenEnvelope(KEY), translations, mapper);

        assertThatThrownBy(() -> disabled.send(notification("Ada", "token")))
                .isInstanceOf(PermanentNotificationException.class)
                .hasMessage("resend_not_configured");
    }

    /**
     * Comprueba que Spring selecciona el constructor de producción aun existiendo un constructor
     * para inyectar el cliente de pruebas.
     */
    @Test
    void springUsesTheProductionConstructorWhenTheHttpClientTestSeamAlsoExists() {
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        ResendProperties properties = new ResendProperties(
                baseUrl, "test-resend-key", "Batch Downloader <no-reply@example.com>",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        MailTemplateProperties mail = new MailTemplateProperties(URI.create("https://batch.example.com"));

        new ApplicationContextRunner()
                .withBean(ResendProperties.class, () -> properties)
                .withBean(MailTemplateProperties.class, () -> mail)
                .withBean(NotificationTokenEnvelope.class, () -> new NotificationTokenEnvelope(KEY))
                .withBean(TranslationCatalogClient.class, () -> translations)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ResendNotificationSender.class)
                .run(context -> assertThat(context).hasSingleBean(ResendNotificationSender.class));
    }

    /**
     * Construye un emisor dirigido al servidor local con un tiempo de petición controlado.
     *
     * @param baseUrl URI del servidor HTTP local de la prueba.
     * @param requestTimeout Tiempo máximo de la petición del escenario.
     * @return emisor aislado para probar el intercambio HTTP.
     */
    private ResendNotificationSender sender(URI baseUrl, Duration requestTimeout) {
        return new ResendNotificationSender(
                new ResendProperties(
                        baseUrl, "test-resend-key", "Batch Downloader <no-reply@example.com>",
                        Duration.ofSeconds(1), requestTimeout),
                new MailTemplateProperties(URI.create("https://batch.example.com")),
                new NotificationTokenEnvelope(KEY), translations, mapper);
    }

    /**
     * Crea un evento de identidad con nombre y sobre de token elegidos por la prueba.
     *
     * @param username Nombre visible usado para comprobar el escape del contenido.
     * @param token Sobre cifrado o alterado elegido por el escenario.
     * @return solicitud de prueba con la identidad del evento reutilizable.
     */
    private EmailNotification notification(String username, String token) {
        String envelope = token.startsWith(NotificationTokenEnvelope.VERSION_PREFIX)
                ? token
                : new NotificationTokenEnvelope(KEY).encrypt(token);
        return new EmailNotification(
                UUID.randomUUID(), Instant.parse("2026-08-08T10:00:00Z"),
                UUID.randomUUID().toString(), null, "person@example.com", "es",
                EmailNotification.Template.MAGIC_LINK,
                Map.of("username", username, "token", envelope, "expiresInMinutes", 15));
    }

    private static Map<String, String> catalog(String greeting) {
        return Map.of(
                "email.magicLink.subject", "Inicia sesión en Batch Downloader",
                "email.magicLink.greeting", greeting,
                "email.magicLink.intro", "Haz",
                "email.magicLink.linkText", "click aquí",
                "email.magicLink.linkSuffix", "para iniciar sesión en Batch Downloader.",
                "email.magicLink.expiry", "Tendrás {minutes}min para entrar.",
                "email.magicLink.doNotShare", "No lo compartas con nadie.",
                "email.magicLink.wrongRecipient", "Si no conoces esta web, alguien puso mal su correo. Puedes ignorar este mensaje.",
                "email.magicLink.logoAlt", "Batch Downloader");
    }

    /**
     * Captura ruta, cabeceras y cuerpo de la petición local y devuelve el estado configurado por el
     * escenario.
     *
     * @param exchange Intercambio HTTP local capturado por el servidor de prueba.
     */
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

    /**
     * Conserva los datos recibidos por el servidor HTTP local para verificar el contrato de envío.
     *
     * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
     * @since 0.1.0
     * @version 0.1.0
     * @category Notificaciones
     */
    private record CapturedRequest(
            String path, String authorization, String idempotencyKey, String body) {}
}
