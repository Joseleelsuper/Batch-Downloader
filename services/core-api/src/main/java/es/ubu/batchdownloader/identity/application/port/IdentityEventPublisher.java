package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.UserAccount;

/** Solicita el correo de acceso con el token recién emitido. */
public interface IdentityEventPublisher {
    void magicLinkRequested(UserAccount user, String rawToken);
}
