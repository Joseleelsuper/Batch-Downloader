package es.ubu.batchdownloader.identity.infrastructure.messaging;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.messaging.OutboxWriter;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Guarda en el outbox una solicitud de correo de acceso con el token cifrado. */
@Component
class IdentityOutboxPublisher implements IdentityEventPublisher {
    private static final String EVENT_TYPE = "notification.email.requested";
    private static final String ROUTING_KEY = "notification.email.requested";

    private final OutboxWriter outbox;
    private final NotificationTokenEnvelope tokenEnvelope;

    IdentityOutboxPublisher(OutboxWriter outbox, NotificationTokenEnvelope tokenEnvelope) {
        this.outbox = outbox;
        this.tokenEnvelope = tokenEnvelope;
    }

    @Override
    public void magicLinkRequested(UserAccount user, String rawToken) {
        outbox.append(
                "user", user.id(), EVENT_TYPE, ROUTING_KEY, UUID.randomUUID(), null,
                Map.of(
                        "recipient", user.email(),
                        "template", "MAGIC_LINK",
                        "parameters", Map.of(
                                "username", user.username(),
                                "token", tokenEnvelope.encrypt(rawToken))));
    }
}
