package es.ubu.batchdownloader.notification.infrastructure.mail;

import es.ubu.batchdownloader.notification.config.MailTemplateProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Envía por SMTP los avisos en español de ZIP disponible o preparación fallida.
 *
 * Construye enlaces a la web pública y muestra la caducidad en la zona configurada. Rechaza las
 * plantillas de identidad, que el enrutador debe enviar mediante Resend.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.RoutingNotificationSender
 * @see es.ubu.batchdownloader.notification.domain.EmailNotification
 * @see es.ubu.batchdownloader.notification.config.MailTemplateProperties
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@Component
public class SmtpNotificationSender {

    /**
     * Idioma y convenciones regionales de los avisos SMTP.
     */
    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");

    /**
     * Cliente SMTP configurado por Spring.
     */
    private final JavaMailSender mailSender;
    /**
     * Remitente y ubicación pública usados por las plantillas.
     */
    private final MailTemplateProperties properties;
    /**
     * Formato inmutable de fecha larga y hora corta en la zona del despliegue.
     */
    private final DateTimeFormatter dateFormatter;

    /**
     * Prepara el cliente SMTP y el formato español de fechas en la zona horaria configurada.
     *
     * @param mailSender Cliente SMTP configurado por Spring.
     * @param properties Remitente, zona horaria y base pública usados en las plantillas.
     */
    public SmtpNotificationSender(JavaMailSender mailSender, MailTemplateProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.dateFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                .withLocale(SPANISH)
                .withZone(properties.resolvedZoneId());
    }

    /**
     * Compone un mensaje de texto de descarga y lo entrega al cliente SMTP.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @throws es.ubu.batchdownloader.notification.application.PermanentNotificationException si
     *     recibe una plantilla de identidad que SMTP no admite.
     *
     * @throws org.springframework.mail.MailException si el cliente SMTP no puede enviar el mensaje.
     */
    public void send(EmailNotification notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(notification.recipient());
        applySpanishTemplate(message, notification);
        mailSender.send(message);
    }

    /**
     * Selecciona el asunto y cuerpo del aviso de descarga; las plantillas de identidad son un fallo
     * permanente.
     *
     * @param message Mensaje SMTP cuyo asunto y cuerpo se completan.
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     */
    private void applySpanishTemplate(SimpleMailMessage message, EmailNotification notification) {
        switch (notification.template()) {
            case EMAIL_VERIFICATION, PASSWORD_RESET ->
                    throw new PermanentNotificationException(
                            "smtp_identity_template_not_supported");
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
     * Compone el aviso de ZIP disponible con enlace al trabajo y fecha de caducidad localizada.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @return cuerpo del correo en texto plano.
     * @throws java.time.format.DateTimeParseException si expiresAt no representa un instante
     *     ISO-8601.
     *
     * @throws IllegalArgumentException si falta jobId o expiresAt.
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
     * Compone el aviso de preparación fallida con identificador del trabajo, código y detalle.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @return cuerpo del correo en texto plano.
     * @throws IllegalArgumentException si falta algún parámetro obligatorio del aviso.
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
     * Prefiere failureCode y acepta errorCode para mensajes compatibles con el formato anterior.
     *
     * @param notification Evento validado, con destinatario, plantilla y parámetros necesarios para
     *     el envío.
     *
     * @return código no vacío y sin espacios exteriores.
     * @throws IllegalArgumentException si no está disponible ninguno de los dos códigos.
     */
    private String failureCode(EmailNotification notification) {
        Object failureCode = notification.parameters().get("failureCode");
        return failureCode == null || failureCode.toString().isBlank()
                ? notification.requiredParameter("errorCode")
                : failureCode.toString().strip();
    }

    /**
     * Añade la ruta del trabajo a la base pública, codificando el identificador como segmento de
     * URI.
     *
     * @param jobId UUID textual del trabajo de descarga.
     * @return enlace al seguimiento y descarga del trabajo en la web.
     */
    private String downloadJobUrl(String jobId) {
        return UriComponentsBuilder.fromUri(properties.publicBaseUrl())
                .pathSegment("downloads", jobId)
                .build()
                .toUriString();
    }
}
