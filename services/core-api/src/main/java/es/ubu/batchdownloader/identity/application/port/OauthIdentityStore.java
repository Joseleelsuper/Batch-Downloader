package es.ubu.batchdownloader.identity.application.port;

import es.ubu.batchdownloader.identity.domain.OauthIdentity;
import java.util.Optional;
import java.util.UUID;

/** Persistencia de identidades externas enlazadas a una cuenta local. */
public interface OauthIdentityStore {
    Optional<OauthIdentity> findByProviderAndSubject(OauthIdentity.Provider provider, String subject);
    boolean existsByUserIdAndProvider(UUID userId, OauthIdentity.Provider provider);
    OauthIdentity save(OauthIdentity identity);
}
