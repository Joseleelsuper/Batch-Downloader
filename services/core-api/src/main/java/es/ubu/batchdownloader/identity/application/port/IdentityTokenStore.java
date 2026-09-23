package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Persiste hashes de enlaces de acceso y serializa su consumo. */
public interface IdentityTokenStore {
    IdentityToken save(IdentityToken token);

    Optional<IdentityToken> findByHash(String tokenHash);

    Optional<IdentityToken> findByHashForUpdate(String tokenHash);

    void invalidateUnconsumedForUser(UUID userId, Instant now);
}
