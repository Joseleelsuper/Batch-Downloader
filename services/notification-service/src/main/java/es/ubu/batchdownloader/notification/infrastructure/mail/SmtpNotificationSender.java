package es.ubu.batchdownloader.notification.infrastructure.mail;

import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.config.MailTemplateProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Implementa el componente {@code SmtpNotificationSender}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
public class SmtpNotificationSender implements NotificationSender {

    /**
     * Constante que define {@code SPANISH}.
     */
    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");

    /**
     * Estado {@code mailSender} mantenido por {@code SmtpNotificationSender}.
     */
    private final JavaMailSender mailSender;
    /**
     * Estado {@code properties} mantenido por {@code SmtpNotificationSender}.
     */
    private final MailTemplateProperties properties;
    /**
     * Estado {@code dateFormatter} mantenido por {@code SmtpNotificationSender}.
     */
    private final DateTimeFormatter dateFormatter;

    /**
     * Inicializa una instancia de {@code SmtpNotificationSender}.
     *
     * @param mailSender Valor de {@code mailSender} utilizado por la operación.
     * @param properties Valor de {@code properties} utilizado por la operación.
     */
    public SmtpNotificationSender(JavaMailSender mailSender, MailTemplateProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.dateFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                .withLocale(SPANISH)
                .withZone(properties.resolvedZoneId());
    }

    /**
     * Envía el contenido solicitado mediante {@code send}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     */
    @Override
    public void send(EmailNotification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(notification.recipient());
        applySpanishTemplate(message, notification);
        mailSender.send(message);
    }

    /**
     * Ejecuta la operación {@code applySpanishTemplate}.
     *
     * @param message Mensaje que debe procesarse.
     * @param notification Valor de {@code notification} utilizado por la operación.
     */
    private void applySpanishTemplate(SimpleMailMessage message, EmailNotification notification) {
        switch (notification.template()) {
            case EMAIL_VERIFICATION -> {
                message.setSubject("Confirma tu correo de Batch Downloader");
                message.setText(emailVerificationBody(notification));
            }
            case PASSWORD_RESET -> {
                message.setSubject("Restablece tu contraseña de Batch Downloader");
                message.setText(passwordResetBody(notification));
            }
            case DOWNLOAD_READY -> {
                message.setSubject("Tu ZIP de Batch Downloader está listo");
                message.setText(downloadReadyBody(notification));
            }
            case DOWNLOAD_FAILED -> {
                message.setSubject("No se pudo preparar tu descarga de Batch Downloader");
                message.setText(downloadFailedBody(notification));
            }
        }
    }

    /**
     * Ejecuta la operación {@code emailVerificationBody}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     * @return Resultado producido por {@code emailVerificationBody}.
     */
    private String emailVerificationBody(EmailNotification notification) {
        return """
                Hola, %s:

                Confirma tu dirección de correo para activar tu cuenta de Batch Downloader:
                %s

                Si no has creado esta cuenta, puedes ignorar este mensaje.
                """.formatted(
                notification.requiredParameter("username"),
                actionUrl("verify-email", notification.requiredParameter("token")));
    }

    /**
     * Ejecuta la operación {@code passwordResetBody}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     * @return Resultado producido por {@code passwordResetBody}.
     */
    private String passwordResetBody(EmailNotification notification) {
        return """
                Hola, %s:

                Usa este enlace para elegir una nueva contraseña de Batch Downloader:
                %s

                Si no has solicitado el cambio, puedes ignorar este mensaje.
                """.formatted(
                notification.requiredParameter("username"),
                actionUrl("reset-password", notification.requiredParameter("token")));
    }

    /**
     * Ejecuta la operación {@code downloadReadyBody}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     * @return Resultado producido por {@code downloadReadyBody}.
     */
    private String downloadReadyBody(EmailNotification notification) {
        Instant expiresAt = Instant.parse(notification.requiredParameter("expiresAt"));
        return """
                Hola:

                Tu paquete de instaladores ya está preparado.

                Descárgalo desde:
                %s

                El enlace caduca el %s.
                Identificador del trabajo: %s

                Este es un mensaje automático de Batch Downloader.
                """.formatted(
                downloadJobUrl(notification.requiredParameter("jobId")),
                dateFormatter.format(expiresAt),
                notification.requiredParameter("jobId"));
    }

    /**
     * Ejecuta la operación {@code downloadFailedBody}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     * @return Resultado producido por {@code downloadFailedBody}.
     */
    private String downloadFailedBody(EmailNotification notification) {
        return """
                Hola:

                No hemos podido preparar tu paquete de instaladores.

                Código: %s
                Detalle: %s
                Identificador del trabajo: %s

                Puedes volver a intentarlo desde Batch Downloader.
                """.formatted(
                failureCode(notification),
                notification.requiredParameter("failureMessage"),
                notification.requiredParameter("jobId"));
    }

    /**
     * Ejecuta la operación {@code failureCode}.
     *
     * @param notification Valor de {@code notification} utilizado por la operación.
     * @return Resultado producido por {@code failureCode}.
     */
    private String failureCode(EmailNotification notification) {
        Object failureCode = notification.parameters().get("failureCode");
        return failureCode == null || failureCode.toString().isBlank()
                ? notification.requiredParameter("errorCode")
                : failureCode.toString().strip();
    }

    /**
     * Ejecuta la operación {@code actionUrl}.
     *
     * @param path Ruta del recurso que debe procesarse.
     * @param token Token utilizado para autorizar o correlacionar la operación.
     * @return Resultado producido por {@code actionUrl}.
     */
    private String actionUrl(String path, String token) {
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8).replace("+", "%20");
        return UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .pathSegment(path)
                .queryParam("token", encodedToken)
                .build(true)
                .toUriString();
    }

    /**
     * Ejecuta la operación {@code downloadJobUrl}.
     *
     * @param jobId Identificador de {@code job} utilizado por la operación.
     * @return Resultado producido por {@code downloadJobUrl}.
     */
    private String downloadJobUrl(String jobId) {
        return UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .pathSegment("downloads", jobId)
                .build()
                .toUriString();
    }
}
