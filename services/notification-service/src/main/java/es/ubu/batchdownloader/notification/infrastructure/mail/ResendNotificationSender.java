package es.ubu.batchdownloader.notification.infrastructure.mail;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import es.ubu.batchdownloader.notification.application.RetryableNotificationException;
import es.ubu.batchdownloader.notification.config.MailTemplateProperties;
import es.ubu.batchdownloader.notification.config.ResendProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
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
 * Envía por Resend los correos de verificación y restablecimiento de contraseña.
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
    private final ObjectMapper mapper;
    private final HttpClient client;

    /**
     * Asocia configuración, descifrado y serialización con el cliente de envío HTTP.
     * El constructor de producción crea un cliente sin redirecciones y con tiempo de conexión
     * acotado.
     *
     * @param properties Endpoint, credenciales y tiempos máximos de Resend.
     * @param mail Base pública utilizada para construir enlaces de verificación y restablecimiento.
     * @param tokens Descifrado autenticado del sobre enc:v1 que recibe de Core.
     * @param mapper Serializador JSON del cuerpo enviado al API de correo.
     */
    @Autowired
    public ResendNotificationSender(
            ResendProperties properties,
            MailTemplateProperties mail,
            NotificationTokenEnvelope tokens,
            ObjectMapper mapper) {
        this(properties, mail, tokens, mapper, HttpClient.newBuilder()
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
     * @param mail Base pública utilizada para construir enlaces de verificación y restablecimiento.
     * @param tokens Descifrado autenticado del sobre enc:v1 que recibe de Core.
     * @param mapper Serializador JSON del cuerpo enviado al API de correo.
     * @param client Cliente HTTP inyectable para controlar respuestas y fallos de transporte.
     */
    ResendNotificationSender(
            ResendProperties properties,
            MailTemplateProperties mail,
            NotificationTokenEnvelope tokens,
            ObjectMapper mapper,
            HttpClient client) {
        this.properties = properties;
        this.mail = mail;
        this.tokens = tokens;
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
        } catch (PermanentNotificationException exception) {
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
     * Descifra el token y compone versiones de texto y HTML de la acción de identidad, escapando el
     * HTML.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @return asunto y cuerpos del correo; nunca una plantilla de descarga.
     * @throws es.ubu.batchdownloader.notification.application.PermanentNotificationException si la
     *     plantilla no pertenece a identidad.
     *
     * @throws IllegalArgumentException si faltan parámetros o el sobre del token no puede
     *     descifrarse.
     */
    private Rendered render(EmailNotification notification) {
        String username = notification.requiredParameter("username");
        String token = tokens.decrypt(notification.requiredParameter("token"));
        String path;
        String subject;
        String action;
        String ignored;
        switch (notification.template()) {
            case EMAIL_VERIFICATION -> {
                path = "verify-email";
                subject = "Confirma tu correo de Batch Downloader";
                action = "Confirmar mi correo";
                ignored = "Si no has creado esta cuenta, puedes ignorar este mensaje.";
            }
            case PASSWORD_RESET -> {
                path = "reset-password";
                subject = "Restablece tu contraseña de Batch Downloader";
                action = "Elegir una nueva contraseña";
                ignored = "Si no has solicitado el cambio, puedes ignorar este mensaje.";
            }
            default -> throw new PermanentNotificationException("resend_template_not_supported");
        }
        String url = actionUrl(path, token);
        String text = "Hola, " + username + ":\n\n" + action + ":\n" + url + "\n\n" + ignored;
        String html = "<p>Hola, " + HtmlUtils.htmlEscape(username) + ":</p>"
                + "<p><a href=\"" + HtmlUtils.htmlEscape(url) + "\">"
                + HtmlUtils.htmlEscape(action) + "</a></p><p>"
                + HtmlUtils.htmlEscape(ignored) + "</p>";
        return new Rendered(subject, text, html);
    }

    /**
     * Construye un enlace público de identidad con el token codificado como parámetro de consulta.
     *
     * @param path Segmento de la pantalla de identidad que recibirá el token.
     * @param token Token descifrado de un solo uso; solo se incorpora al enlace enviado al
     *     destinatario.
     *
     * @return URI de la pantalla de verificación o restablecimiento.
     */
    private String actionUrl(String path, String token) {
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
        return UriComponentsBuilder.fromUri(mail.publicBaseUrl())
                .pathSegment(path)
                .queryParam("token", encodedToken)
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
