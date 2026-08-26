package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.OauthIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataOauthIdentityRepository extends JpaRepository<OauthIdentityEntity, UUID> {
    Optional<OauthIdentityEntity> findByProviderAndSubject(OauthIdentity.Provider provider, String subject);
    boolean existsByUserIdAndProvider(UUID userId, OauthIdentity.Provider provider);
}
