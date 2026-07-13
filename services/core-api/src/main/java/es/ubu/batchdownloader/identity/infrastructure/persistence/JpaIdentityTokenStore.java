package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaIdentityTokenStore implements IdentityTokenStore {
    private final SpringDataIdentityTokenRepository repository;

    JpaIdentityTokenStore(SpringDataIdentityTokenRepository repository) {
        this.repository = repository;
    }

    @Override
    public IdentityToken save(IdentityToken token) {
        IdentityTokenEntity entity = repository.findById(token.id()).orElseGet(() -> IdentityTokenEntity.from(token));
        entity.updateFrom(token);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<IdentityToken> findByHashAndType(String tokenHash, IdentityToken.Type type) {
        return repository.findByTokenHashAndType(tokenHash, type).map(IdentityTokenEntity::toDomain);
    }

    @Override
    public void invalidateUnconsumedForUser(UUID userId, IdentityToken.Type type) {
        repository.deleteByUserIdAndTypeAndConsumedAtIsNull(userId, type);
    }
}
