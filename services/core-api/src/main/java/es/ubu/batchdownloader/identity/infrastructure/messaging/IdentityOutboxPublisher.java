package es.ubu.batchdownloader.identity.infrastructure.messaging;

import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.messaging.OutboxWriter;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Publica los datos gestionados por {@code IdentityOutboxPublisher}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Component
class IdentityOutboxPublisher implements IdentityEventPublisher {
    /**
     * Constante que define {@code EVENT_TYPE}.
     */
    private static final String EVENT_TYPE = "notification.email.requested";
    /**
     * Constante que define {@code ROUTING_KEY}.
     */
    private static final String ROUTING_KEY = "notification.email.requested";
    /**
     * Estado {@code outbox} mantenido por {@code IdentityOutboxPublisher}.
     */
    private final OutboxWriter outbox;
    private final NotificationTokenCipher tokenCipher;

    /**
     * Inicializa una instancia de {@code IdentityOutboxPublisher}.
     *
     * @param outbox Valor de {@code outbox} utilizado por la operación.
     */
    IdentityOutboxPublisher(OutboxWriter outbox, NotificationTokenCipher tokenCipher) {
        this.outbox = outbox;
        this.tokenCipher = tokenCipher;
    }

    /**
     * Implementa {@code emailVerificationRequested} para {@code IdentityOutboxPublisher}.
     *
     * @param user Valor de {@code user} utilizado por la operación.
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     */
    @Override
    public void emailVerificationRequested(UserAccount user, String rawToken) {
        append(user, "EMAIL_VERIFICATION", rawToken);
    }

    /**
     * Implementa {@code passwordResetRequested} para {@code IdentityOutboxPublisher}.
     *
     * @param user Valor de {@code user} utilizado por la operación.
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     */
    @Override
    public void passwordResetRequested(UserAccount user, String rawToken) {
        append(user, "PASSWORD_RESET", rawToken);
    }

    /**
     * Ejecuta la operación {@code append}.
     *
     * @param user Valor de {@code user} utilizado por la operación.
     * @param template Valor de {@code template} utilizado por la operación.
     * @param rawToken Valor de {@code rawToken} utilizado por la operación.
     */
    private void append(UserAccount user, String template, String rawToken) {
        UUID correlationId = UUID.randomUUID();
        outbox.append(
                "user", user.id(), EVENT_TYPE, ROUTING_KEY, correlationId, null,
                Map.of(
                        "recipient", user.email(),
                        "template", template,
                        "parameters", Map.of(
                                "username", user.username(),
                                "token", tokenCipher.encrypt(rawToken))));
    }
}
