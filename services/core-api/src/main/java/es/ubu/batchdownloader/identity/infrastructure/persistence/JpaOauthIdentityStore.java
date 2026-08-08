package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.OauthIdentityStore;
import es.ubu.batchdownloader.identity.domain.OauthIdentity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaOauthIdentityStore implements OauthIdentityStore {
    private final SpringDataOauthIdentityRepository repository;

    JpaOauthIdentityStore(SpringDataOauthIdentityRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<OauthIdentity> findByProviderAndSubject(
            OauthIdentity.Provider provider, String subject) {
        return repository.findByProviderAndSubject(provider, subject).map(OauthIdentityEntity::toDomain);
    }

    @Override
    public boolean existsByUserIdAndProvider(UUID userId, OauthIdentity.Provider provider) {
        return repository.existsByUserIdAndProvider(userId, provider);
    }

    @Override
    public OauthIdentity save(OauthIdentity identity) {
        OauthIdentityEntity entity = repository.findById(identity.id())
                .orElseGet(() -> OauthIdentityEntity.from(identity));
        entity.updateFrom(identity);
        return repository.save(entity).toDomain();
    }
}
