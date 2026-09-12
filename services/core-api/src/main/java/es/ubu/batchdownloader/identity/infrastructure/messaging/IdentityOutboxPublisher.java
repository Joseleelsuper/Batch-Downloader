package es.ubu.batchdownloader.identity.infrastructure.messaging;

import es.ubu.batchdownloader.contracts.crypto.NotificationTokenEnvelope;
import es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import es.ubu.batchdownloader.messaging.OutboxWriter;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Solicita correo de verificación o recuperación guardando el token cifrado, nunca en claro, en el
 * outbox de la cuenta.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.application.port.IdentityEventPublisher
 * @see es.ubu.batchdownloader.messaging.OutboxWriter
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Component
class IdentityOutboxPublisher implements IdentityEventPublisher {
    /**
     * Valor compartido que fija e v e n t  t y p e para el comportamiento del componente.
     */
    private static final String EVENT_TYPE = "notification.email.requested";
    /**
     * Constante de protocolo que identifica o protege r o u t i n g  k e y.
     */
    private static final String ROUTING_KEY = "notification.email.requested";
    /**
     * Estado {@code outbox} mantenido por {@code IdentityOutboxPublisher}.
     */
    private final OutboxWriter outbox;
    private final NotificationTokenEnvelope tokenEnvelope;

    /**
     * Conecta el outbox transaccional y el cifrado de los permisos enviados por correo.
     *
     * @param outbox Escritor durable que participa en la transacción de la cuenta y su token.
     * @param tokenEnvelope Cifrador autenticado del token enviado por mensajería al servicio de
     *     notificaciones.
     */
    IdentityOutboxPublisher(OutboxWriter outbox, NotificationTokenEnvelope tokenEnvelope) {
        this.outbox = outbox;
        this.tokenEnvelope = tokenEnvelope;
    }

    /**
     * Registra una solicitud de plantilla EMAIL_VERIFICATION con el permiso cifrado para confirmar
     * el correo.
     *
     * @param user Cuenta destinataria de la consulta, token, evento o proyección.
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
     */
    @Override
    public void emailVerificationRequested(UserAccount user, String rawToken) {
        append(user, "EMAIL_VERIFICATION", rawToken);
    }

    /**
     * Registra una solicitud de plantilla PASSWORD_RESET con el permiso cifrado para renovar la
     * contraseña.
     *
     * @param user Cuenta destinataria de la consulta, token, evento o proyección.
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
     */
    @Override
    public void passwordResetRequested(UserAccount user, String rawToken) {
        append(user, "PASSWORD_RESET", rawToken);
    }

    /**
     * Genera correlación independiente y conserva destinatario, plantilla, nombre visible y token
     * cifrado en notification.email.requested.
     *
     * @param user Cuenta destinataria de la consulta, token, evento o proyección.
     * @param template Plantilla EMAIL_VERIFICATION o PASSWORD_RESET que recibirá el permiso
     *     cifrado.
     * @param rawToken Token opaco sin hash; solo debe enviarse al usuario por el canal previsto,
     *     nunca registrarse.
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
                                "token", tokenEnvelope.encrypt(rawToken))));
    }
}
