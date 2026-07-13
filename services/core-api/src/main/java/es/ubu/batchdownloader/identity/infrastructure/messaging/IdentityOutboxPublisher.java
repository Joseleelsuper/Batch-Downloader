package es.ubu.batchdownloader.identity.infrastructure.messaging;

import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.messaging.OutboxWriter;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class IdentityOutboxPublisher implements IdentityEventPublisher {
    private static final String EVENT_TYPE = "notification.email.requested";
    private static final String ROUTING_KEY = "notification.email.requested";
    private final OutboxWriter outbox;

    IdentityOutboxPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    @Override
    public void emailVerificationRequested(UserAccount user, String rawToken) {
        append(user, "EMAIL_VERIFICATION", rawToken);
    }

    @Override
    public void passwordResetRequested(UserAccount user, String rawToken) {
        append(user, "PASSWORD_RESET", rawToken);
    }

    private void append(UserAccount user, String template, String rawToken) {
        UUID correlationId = UUID.randomUUID();
        outbox.append(
                "user", user.id(), EVENT_TYPE, ROUTING_KEY, correlationId, null,
                Map.of(
                        "recipient", user.email(),
                        "template", template,
                        "parameters", Map.of("username", user.username(), "token", rawToken)));
    }
}
