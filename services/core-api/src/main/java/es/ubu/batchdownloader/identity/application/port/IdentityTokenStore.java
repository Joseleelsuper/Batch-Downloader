package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;

public interface IdentityTokenStore {
    IdentityToken save(IdentityToken token);
    Optional<IdentityToken> findByHashAndType(String tokenHash, IdentityToken.Type type);
    void invalidateUnconsumedForUser(java.util.UUID userId, IdentityToken.Type type);
}
