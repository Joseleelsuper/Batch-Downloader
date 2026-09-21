package es.ubu.batchdownloader.notification.infrastructure.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import es.ubu.batchdownloader.notification.config.MailTemplateProperties;
import es.ubu.batchdownloader.notification.config.ResendProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import es.ubu.batchdownloader.notification.infrastructure.translation.TranslationCatalogClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Envía por Resend los enlaces mágicos de acceso.
 *
 * Descifra el token únicamente al componer el enlace y usa eventId como clave de idempotencia.
 * Los estados 429 y 5xx admiten reintento; otros rechazos y errores de contenido son permanentes.
 *
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.RoutingNotificationSender
 * @see es.ubu.batchdownloader.notification.application.RetryableNotificationException
 * @see es.ubu.batchdownloader.notification.application.PermanentNotificationException
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
public class ResendNotificationSender {
    private final ResendProperties properties;
    private final MailTemplateProperties mail;
    private final NotificationTokenEnvelope tokens;
    private final TranslationCatalogClient translations;
    private final ObjectMapper mapper;
    private final HttpClient client;

    /**
     * Asocia configuración, descifrado y serialización con el cliente de envío HTTP.
     * El constructor de producción crea un cliente sin redirecciones y con tiempo de conexión
     * acotado.
     *
     * @param properties Endpoint, credenciales y tiempos máximos de Resend.
     * @param mail Base pública utilizada para construir el enlace mágico de acceso.
     * @param tokens Descifrado autenticado del sobre enc:v1 que recibe de Core.
     * @param translations Cliente interno de catálogos de correo.
     * @param mapper Serializador JSON del cuerpo enviado al API de correo.
     */
    @Autowired
    public ResendNotificationSender(
            ResendProperties properties,
            MailTemplateProperties mail,
            NotificationTokenEnvelope tokens,
            TranslationCatalogClient translations,
            ObjectMapper mapper) {
        this(properties, mail, tokens, translations, mapper, HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    /**
     * Asocia configuración, descifrado y serialización con el cliente de envío HTTP.
     * El constructor de producción crea un cliente sin redirecciones y con tiempo de conexión
     * acotado.
     *
     * @param properties Endpoint, credenciales y tiempos máximos de Resend.
     * @param mail Base pública utilizada para construir el enlace mágico de acceso.
     * @param tokens Descifrado autenticado del sobre enc:v1 que recibe de Core.
     * @param translations Cliente interno de catálogos de correo.
     * @param mapper Serializador JSON del cuerpo enviado al API de correo.
     * @param client Cliente HTTP inyectable para controlar respuestas y fallos de transporte.
     */
    ResendNotificationSender(
            ResendProperties properties,
            MailTemplateProperties mail,
            NotificationTokenEnvelope tokens,
            TranslationCatalogClient translations,
            ObjectMapper mapper,
            HttpClient client) {
        this.properties = properties;
        this.mail = mail;
        this.tokens = tokens;
        this.translations = translations;
        this.mapper = mapper;
        this.client = client;
    }

    /**
     * Compone y envía un correo de identidad con idempotencia por evento y clasifica la respuesta
     * del proveedor.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @throws es.ubu.batchdownloader.notification.application.PermanentNotificationException si
     *     falta configuración, el contenido no es válido o el proveedor rechaza la petición sin indicar
     *     un fallo temporal.
     *
     * @throws es.ubu.batchdownloader.notification.application.RetryableNotificationException si
     *     recibe 429 o 5xx, falla la E/S o se interrumpe el envío.
     */
    public void send(EmailNotification notification) {
        if (!properties.enabled()) {
            throw new PermanentNotificationException("resend_not_configured");
        }
        Rendered rendered;
        try {
            rendered = render(notification);
        } catch (PermanentNotificationException | RetryableNotificationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new PermanentNotificationException("resend_notification_invalid");
        }
        String payload;
        try {
            payload = mapper.writeValueAsString(Map.of(
                    "from", properties.from(),
                    "to", new String[] {notification.recipient()},
                    "subject", rendered.subject(),
                    "text", rendered.text(),
                    "html", rendered.html()));
        } catch (JsonProcessingException exception) {
            throw new PermanentNotificationException("resend_payload_invalid");
        }

        URI endpoint = properties.baseUrl().resolve("/emails");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.requestTimeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", notification.eventId().toString())
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) return;
            if (status == 429 || status >= 500) {
                throw new RetryableNotificationException(
                        "resend_temporarily_unavailable", parseRetryAfter(response));
            }
            throw new PermanentNotificationException("resend_request_rejected");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryableNotificationException("resend_interrupted", exception);
        } catch (IOException exception) {
            throw new RetryableNotificationException("resend_io_failure", exception);
        }
    }

    /**
     * Descifra el token y compone versiones de texto y HTML del correo localizado, escapando el
     * HTML.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @return asunto y cuerpos del correo de acceso.
     * @throws es.ubu.batchdownloader.notification.application.PermanentNotificationException si la
     *     plantilla no pertenece a identidad.
     *
     * @throws IllegalArgumentException si faltan parámetros o el sobre del token no puede
     *     descifrarse.
     */
    private Rendered render(EmailNotification notification) {
        String token = tokens.decrypt(notification.requiredParameter("token"));
        Map<String, String> catalog = translations.catalog(notification.locale());
        switch (notification.template()) {
            case MAGIC_LINK -> { }
            default -> throw new PermanentNotificationException("resend_template_not_supported");
        }
        String url = actionUrl("login", token);
        String greeting = message(catalog, "email.magicLink.greeting");
        String intro = message(catalog, "email.magicLink.intro");
        String linkText = message(catalog, "email.magicLink.linkText");
        String linkSuffix = message(catalog, "email.magicLink.linkSuffix");
        String expiry = message(catalog, "email.magicLink.expiry")
                .replace("{minutes}", Long.toString(notification.expiryMinutes()));
        String doNotShare = message(catalog, "email.magicLink.doNotShare");
        String wrongRecipient = message(catalog, "email.magicLink.wrongRecipient");
        String logoAlt = message(catalog, "email.magicLink.logoAlt");
        String text = greeting + "\n\n"
                + intro + " " + linkText + " (" + url + ") " + linkSuffix
                + "\n\n" + expiry + " " + doNotShare
                + "\n\n" + wrongRecipient;
        String html = "<div style=\"font-family:Arial,sans-serif;line-height:1.5;max-width:600px;"
                + "margin:0 auto;padding:24px\">"
                + "<p style=\"text-align:center;margin:0 0 24px\"><img src=\""
                + HtmlUtils.htmlEscape(mail.logoUrl().toString()) + "\" alt=\""
                + HtmlUtils.htmlEscape(logoAlt)
                + "\" style=\"display:block;width:100%;max-width:420px;height:auto;margin:0 auto\"></p>"
                + "<p>" + HtmlUtils.htmlEscape(greeting) + "</p>"
                + "<p>" + HtmlUtils.htmlEscape(intro) + " <a href=\""
                + HtmlUtils.htmlEscape(url) + "\">" + HtmlUtils.htmlEscape(linkText)
                + "</a> " + HtmlUtils.htmlEscape(linkSuffix) + "</p>"
                + "<p>" + HtmlUtils.htmlEscape(expiry) + " "
                + HtmlUtils.htmlEscape(doNotShare) + "</p>"
                + "<p>" + HtmlUtils.htmlEscape(wrongRecipient) + "</p></div>";
        return new Rendered(message(catalog, "email.magicLink.subject"), text, html);
    }

    private String message(Map<String, String> catalog, String key) {
        String value = catalog.get(key);
        if (value == null || value.isBlank()) {
            throw new PermanentNotificationException("resend_translation_missing");
        }
        return value;
    }

    /**
     * Construye un enlace público de identidad con el token codificado en el fragmento.
     *
     * @param path Segmento de la pantalla de identidad que recibirá el token.
     * @param token Token descifrado de un solo uso; solo se incorpora al enlace enviado al
     *     destinatario.
     *
     * @return URI de la pantalla de acceso que recibirá el token.
     */
    private String actionUrl(String path, String token) {
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
        return UriComponentsBuilder.fromUri(mail.publicBaseUrl())
                .pathSegment(path)
                .fragment("token=" + encodedToken)
                .build(true)
                .toUriString();
    }

    /**
     * Interpreta Retry-After numérico en segundos y lo acota entre uno y 300 segundos.
     *
     * @param response Respuesta HTTP cuyo Retry-After puede indicar segundos de espera.
     * @return demora acotada; un segundo cuando la cabecera falta o no es un entero.
     */
    private static Duration parseRetryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value.strip());
                        return java.util.Optional.of(Duration.ofSeconds(Math.max(1, Math.min(seconds, 300))));
                    } catch (NumberFormatException ignored) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(Duration.ofSeconds(1));
    }

    /**
     * Reúne el asunto y las versiones texto/HTML de un único correo de identidad listo para
     * serializar.
     *
     * @see es.ubu.batchdownloader.notification.infrastructure.mail.ResendNotificationSender
     * @since 0.1.0
     * @version 0.1.0
     * @category Notificaciones
     */
    private record Rendered(String subject, String text, String html) {}
}
