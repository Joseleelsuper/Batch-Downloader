package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.UserAccount;

public interface IdentityEventPublisher {
    void emailVerificationRequested(UserAccount user, String rawToken);
    void passwordResetRequested(UserAccount user, String rawToken);
}
