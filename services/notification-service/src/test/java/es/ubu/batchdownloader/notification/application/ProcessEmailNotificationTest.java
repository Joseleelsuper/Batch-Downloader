package es.ubu.batchdownloader.notification.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import es.ubu.batchdownloader.notification.application.port.NotificationInbox;
import es.ubu.batchdownloader.notification.application.port.NotificationSender;
import es.ubu.batchdownloader.notification.domain.EmailNotification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProcessEmailNotificationTest {

    private static final UUID EVENT_ID = UUID.fromString("83e7ddfe-0fb4-4f19-9694-137ada2bb39c");

    @Mock
    private NotificationInbox inbox;

    @Mock
    private NotificationSender sender;

    private ProcessEmailNotification processor;
    private EmailNotification notification;

    @BeforeEach
    void setUp() {
        processor = new ProcessEmailNotification(inbox, sender);
        notification = new EmailNotification(
                EVENT_ID,
                Instant.parse("2026-07-11T10:00:00Z"),
                "download-job-84338aa2",
                null,
                "persona@example.com",
                EmailNotification.Template.DOWNLOAD_READY,
                Map.of(
                        "jobId", "84338aa2-b2f0-47d1-9054-5760ac883d74",
                        "downloadUrl", "https://downloads.example.com/job.zip",
                        "expiresAt", "2026-07-12T10:00:00Z"));
    }

    @Test
    void sendsAndMarksANewEventAsProcessed() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.ACQUIRED);

        processor.execute(notification);

        verify(sender).send(notification);
        verify(inbox).markProcessed(EVENT_ID);
    }

    @Test
    void ignoresAnEventAlreadyProcessed() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.ALREADY_PROCESSED);

        processor.execute(notification);

        verify(sender, never()).send(notification);
        verify(inbox, never()).markProcessed(EVENT_ID);
    }

    @Test
    void recordsTheFailureAndPropagatesItForRabbitRetry() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.ACQUIRED);
        org.springframework.mail.MailSendException mailFailure =
                new org.springframework.mail.MailSendException("SMTP no disponible");
        org.mockito.Mockito.doThrow(mailFailure).when(sender).send(notification);

        assertThatThrownBy(() -> processor.execute(notification))
                .isInstanceOf(NotificationProcessingException.class)
                .hasCause(mailFailure);

        verify(inbox).markFailed(EVENT_ID, "SMTP no disponible");
        verify(inbox, never()).markProcessed(EVENT_ID);
    }

    @Test
    void rejectsAnEventThatIsBeingProcessedByAnotherConsumer() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.BUSY);

        assertThatThrownBy(() -> processor.execute(notification))
                .isInstanceOf(NotificationProcessingException.class)
                .hasMessageContaining(EVENT_ID.toString());

        verify(sender, never()).send(notification);
    }
}
