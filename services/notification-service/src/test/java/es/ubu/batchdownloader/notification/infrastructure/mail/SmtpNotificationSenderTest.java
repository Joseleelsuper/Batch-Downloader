package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

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

@ExtendWith(MockitoExtension.class)
class SmtpNotificationSenderTest {

    @Mock
    private JavaMailSender mailSender;

    private SmtpNotificationSender sender;

    @BeforeEach
    void setUp() {
        sender = new SmtpNotificationSender(
                mailSender,
                new MailTemplateProperties(
                        "no-reply@example.com",
                        "Europe/Madrid",
                        URI.create("https://batch.example.com")));
    }

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

    @Test
    void rendersTheSpanishEmailVerificationTemplate() {
        EmailNotification notification = notification(
                EmailNotification.Template.EMAIL_VERIFICATION,
                Map.of("username", "Ada", "token", "a token+with/slashes"));

        SimpleMailMessage email = sendAndCapture(notification);

        assertThat(email.getSubject()).isEqualTo("Confirma tu correo de Batch Downloader");
        assertThat(email.getText())
                .contains("Hola, Ada")
                .contains("https://batch.example.com/verify-email")
                .contains("token=a%20token%2Bwith%2Fslashes");
    }

    @Test
    void rendersTheSpanishPasswordResetTemplate() {
        EmailNotification notification = notification(
                EmailNotification.Template.PASSWORD_RESET,
                Map.of("username", "Ada", "token", "reset-token"));

        SimpleMailMessage email = sendAndCapture(notification);

        assertThat(email.getSubject()).isEqualTo("Restablece tu contraseña de Batch Downloader");
        assertThat(email.getText())
                .contains("Hola, Ada")
                .contains("https://batch.example.com/reset-password?token=reset-token");
    }

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

    private SimpleMailMessage sendAndCapture(EmailNotification notification) {
        sender.send(notification);
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
