package es.ubu.batchdownloader.identity.application.port;

import java.util.UUID;

/** Solicita el correo de acceso con el token recién emitido. */
public interface IdentityEventPublisher {
    void magicLinkRequested(
            String aggregateType,
            UUID aggregateId,
            String recipient,
            String rawToken,
            String locale,
            long expiresInMinutes);
}
