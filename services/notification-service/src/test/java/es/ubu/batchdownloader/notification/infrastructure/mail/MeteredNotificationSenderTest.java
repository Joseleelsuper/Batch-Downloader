package es.ubu.batchdownloader.notification.infrastructure.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import es.ubu.batchdownloader.notification.domain.EmailNotification;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifica la observabilidad acotada alrededor del router de correo. */
class MeteredNotificationSenderTest {

    @Test
    void recordsSuccessWithoutRecipientOrEventTags() {
        RoutingNotificationSender delegate = mock(RoutingNotificationSender.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredNotificationSender sender = new MeteredNotificationSender(
                delegate,
                Optional.of(registry));
        EmailNotification notification = notification();

        sender.send(notification);

        verify(delegate).send(notification);
        assertThat(registry.get("notification_send")
                .tags("template", "download_ready", "outcome", "success")
                .timer()
                .count()).isEqualTo(1);
        assertThat(registry.get("notification_send").timer().getId().getTags())
                .noneMatch(tag -> tag.getKey().equals("recipient")
                        || tag.getKey().equals("eventId"));
    }

    @Test
    void preservesFailureAndRecordsIt() {
        RoutingNotificationSender delegate = mock(RoutingNotificationSender.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MeteredNotificationSender sender = new MeteredNotificationSender(
                delegate,
                Optional.of(registry));
        EmailNotification notification = notification();
        IllegalStateException failure = new IllegalStateException("mail unavailable");
        doThrow(failure).when(delegate).send(notification);

        assertThatThrownBy(() -> sender.send(notification)).isSameAs(failure);

        assertThat(registry.get("notification_send")
                .tags("template", "download_ready", "outcome", "failure")
                .timer()
                .count()).isEqualTo(1);
    }

    private EmailNotification notification() {
        return new EmailNotification(
                UUID.randomUUID(),
                Instant.parse("2026-08-22T08:00:00Z"),
                "download-job",
                null,
                "person@example.test",
                EmailNotification.Template.DOWNLOAD_READY,
                Map.of());
    }
}
