package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.PendingMagicLink;
import java.time.Instant;
import java.util.Optional;

/** Persiste solicitudes de acceso antes de que exista una cuenta. */
public interface PendingMagicLinkStore {
    PendingMagicLink save(PendingMagicLink request);

    Optional<PendingMagicLink> findByNormalizedEmailForUpdate(String normalizedEmail);

    Optional<PendingMagicLink> findByHashForUpdate(String tokenHash);

    int deleteExpiredOrConsumed(Instant now);
}
