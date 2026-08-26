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

/**
 * Agrupa los escenarios de prueba de {@code ProcessEmailNotificationTest}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@ExtendWith(MockitoExtension.class)
class ProcessEmailNotificationTest {

    /**
     * Constante que define {@code EVENT_ID}.
     */
    private static final UUID EVENT_ID = UUID.fromString("83e7ddfe-0fb4-4f19-9694-137ada2bb39c");

    /**
     * Dato compartido {@code inbox} para los escenarios de prueba.
     */
    @Mock
    private NotificationInbox inbox;

    /**
     * Dato compartido {@code sender} para los escenarios de prueba.
     */
    @Mock
    private NotificationSender sender;

    /**
     * Dato compartido {@code processor} para los escenarios de prueba.
     */
    private ProcessEmailNotification processor;
    /**
     * Dato compartido {@code notification} para los escenarios de prueba.
     */
    private EmailNotification notification;

    /**
     * Prepara el estado necesario para los escenarios de prueba.
     */
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

    /**
     * Comprueba el escenario {@code sendsAndMarksANewEventAsProcessed}.
     */
    @Test
    void sendsAndMarksANewEventAsProcessed() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.ACQUIRED);

        processor.execute(notification);

        verify(sender).send(notification);
        verify(inbox).markProcessed(EVENT_ID);
    }

    /**
     * Comprueba el escenario {@code ignoresAnEventAlreadyProcessed}.
     */
    @Test
    void ignoresAnEventAlreadyProcessed() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.ALREADY_PROCESSED);

        processor.execute(notification);

        verify(sender, never()).send(notification);
        verify(inbox, never()).markProcessed(EVENT_ID);
    }

    /**
     * Comprueba el escenario {@code recordsTheFailureAndPropagatesItForRabbitRetry}.
     */
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

    /**
     * Comprueba el escenario {@code rejectsAnEventThatIsBeingProcessedByAnotherConsumer}.
     */
    @Test
    void rejectsAnEventThatIsBeingProcessedByAnotherConsumer() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.BUSY);

        assertThatThrownBy(() -> processor.execute(notification))
                .isInstanceOf(NotificationProcessingException.class)
                .hasMessageContaining(EVENT_ID.toString());

        verify(sender, never()).send(notification);
    }

    @Test
    void preservesRetryAfterMetadataForRabbitRetryHandling() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.ACQUIRED);
        RetryableNotificationException failure = new RetryableNotificationException(
                "resend_temporarily_unavailable", java.time.Duration.ofSeconds(12));
        org.mockito.Mockito.doThrow(failure).when(sender).send(notification);

        assertThatThrownBy(() -> processor.execute(notification))
                .isSameAs(failure)
                .isInstanceOfSatisfying(RetryableNotificationException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.retryAfter())
                                .isEqualTo(java.time.Duration.ofSeconds(12)));
        verify(inbox).markFailed(EVENT_ID, "resend_temporarily_unavailable");
    }

    @Test
    void propagatesPermanentFailuresForImmediateDeadLettering() {
        when(inbox.claim(EVENT_ID, EmailNotification.EVENT_TYPE))
                .thenReturn(NotificationInbox.ClaimResult.ACQUIRED);
        PermanentNotificationException failure =
                new PermanentNotificationException("resend_request_rejected");
        org.mockito.Mockito.doThrow(failure).when(sender).send(notification);

        assertThatThrownBy(() -> processor.execute(notification)).isSameAs(failure);
        verify(inbox).markFailed(EVENT_ID, "resend_request_rejected");
    }
}
