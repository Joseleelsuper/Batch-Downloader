package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class RoutingNotificationSenderTest {
    @Test
    void routesOnlyAuthenticationMailThroughResend() {
        ResendNotificationSender resend = Mockito.mock(ResendNotificationSender.class);
        SmtpNotificationSender smtp = Mockito.mock(SmtpNotificationSender.class);
        RoutingNotificationSender routing = new RoutingNotificationSender(resend, smtp);
        EmailNotification verification = notification(EmailNotification.Template.EMAIL_VERIFICATION);

        routing.send(verification);

        verify(resend).send(verification);
        verifyNoInteractions(smtp);
    }

    @Test
    void keepsDownloadMailOnSmtp() {
        ResendNotificationSender resend = Mockito.mock(ResendNotificationSender.class);
        SmtpNotificationSender smtp = Mockito.mock(SmtpNotificationSender.class);
        RoutingNotificationSender routing = new RoutingNotificationSender(resend, smtp);
        EmailNotification ready = notification(EmailNotification.Template.DOWNLOAD_READY);

        routing.send(ready);

        verify(smtp).send(ready);
        verifyNoInteractions(resend);
    }

    private static EmailNotification notification(EmailNotification.Template template) {
        return new EmailNotification(
                UUID.randomUUID(), Instant.parse("2026-08-08T10:00:00Z"),
                UUID.randomUUID().toString(), null, "person@example.com", template,
                template == EmailNotification.Template.EMAIL_VERIFICATION
                        ? Map.of("username", "person", "token", "legacy-token")
                        : Map.of("jobId", UUID.randomUUID().toString()));
    }
}
