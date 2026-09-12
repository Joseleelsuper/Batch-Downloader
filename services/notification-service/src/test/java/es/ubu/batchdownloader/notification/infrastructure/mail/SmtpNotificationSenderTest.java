package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import es.ubu.batchdownloader.notification.application.PermanentNotificationException;
import es.ubu.batchdownloader.notification.config.MailTemplateProperties;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Comprueba asuntos, cuerpos y enlaces españoles de descarga con un cliente SMTP simulado.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.notification.infrastructure.mail.SmtpNotificationSender
 * @since 0.1.0
 * @version 0.1.0
 * @category Notificaciones
 */
@ExtendWith(MockitoExtension.class)
class SmtpNotificationSenderTest {

    /**
     * Dato compartido {@code mailSender} para los escenarios de prueba.
     */
    @Mock
    private JavaMailSender mailSender;

    /**
     * Dato compartido {@code sender} para los escenarios de prueba.
     */
    private SmtpNotificationSender sender;

    /**
     * Prepara el emisor SMTP simulado y la base pública y zona horaria usadas por las plantillas.
     */
    @BeforeEach
    void setUp() {
        sender = new SmtpNotificationSender(
                mailSender,
                new MailTemplateProperties(
                        "no-reply@example.com",
                        "Europe/Madrid",
                        URI.create("https://batch.example.com")));
    }

    /**
     * Comprueba que el aviso de ZIP disponible contiene enlace público, trabajo y caducidad
     * localizada.
     */
    @Test
    void rendersTheSpanishDownloadReadyTemplate() {
        EmailNotification notification = notification(
                EmailNotification.Template.DOWNLOAD_READY,
                Map.of(
                        "jobId", "84338aa2-b2f0-47d1-9054-5760ac883d74",
                        "expiresAt", "2026-07-12T10:00:00Z"));

        SimpleMailMessage email = sendAndCapture(notification);

        assertThat(email.getTo()).containsExactly("persona@example.com");
        assertThat(email.getSubject()).isEqualTo("Tu ZIP de Batch Downloader está listo");
        assertThat(email.getText())
                .contains("Tu paquete de instaladores ya está preparado")
                .contains("https://batch.example.com/downloads/84338aa2-b2f0-47d1-9054-5760ac883d74")
                .contains("84338aa2-b2f0-47d1-9054-5760ac883d74");
    }

    /**
     * Comprueba que el aviso de fallo conserva el detalle y acepta errorCode como alternativa al
     * código actual.
     */
    @Test
    void rendersTheSpanishDownloadFailureTemplateWithErrorCodeFallback() {
        EmailNotification notification = notification(
                EmailNotification.Template.DOWNLOAD_FAILED,
                Map.of(
                        "jobId", "84338aa2-b2f0-47d1-9054-5760ac883d74",
                        "errorCode", "REMOTE_DOWNLOAD_FAILED",
                        "failureMessage", "No se pudo recuperar el instalador"));

        SimpleMailMessage email = sendAndCapture(notification);

        assertThat(email.getSubject())
                .isEqualTo("No se pudo preparar tu descarga de Batch Downloader");
        assertThat(email.getText())
                .contains("No hemos podido preparar tu paquete")
                .contains("REMOTE_DOWNLOAD_FAILED")
                .contains("No se pudo recuperar el instalador");
    }

    /**
     * Comprueba que SMTP rechaza las plantillas de identidad mediante un fallo permanente.
     */
    @Test
    void rejectsIdentityTemplatesThatBelongToResend() {
        EmailNotification notification = notification(
                EmailNotification.Template.EMAIL_VERIFICATION,
                Map.of("username", "Ada", "token", "enc:v1:envelope"));

        assertThatThrownBy(() -> sender.send(notification))
                .isInstanceOf(PermanentNotificationException.class)
                .hasMessage("smtp_identity_template_not_supported");
        verifyNoInteractions(mailSender);
    }

    /**
     * Construye un evento válido con la plantilla y parámetros que necesita cada escenario SMTP.
     *
     * @param template Plantilla de correo elegida por el escenario.
     * @param parameters Parámetros controlados que se validan o renderizan.
     * @return solicitud local de prueba.
     */
    private EmailNotification notification(
            EmailNotification.Template template,
            Map<String, Object> parameters) {
        return new EmailNotification(
                UUID.randomUUID(),
                Instant.parse("2026-07-11T10:00:00Z"),
                "correlation-123",
                null,
                "persona@example.com",
                template,
                parameters);
    }

    /**
     * Invoca al emisor y captura el mensaje que recibe el cliente SMTP simulado.
     *
     * @param notification Solicitud de correo preparada por el escenario de prueba.
     * @return mensaje listo para comprobar asunto, destinatario y cuerpo.
     */
    private SimpleMailMessage sendAndCapture(EmailNotification notification) {
        sender.send(notification);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
