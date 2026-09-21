package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/** Adapta persistencia JPA al contrato de enlaces de acceso. */
@Repository
class JpaIdentityTokenStore implements IdentityTokenStore {
    private final SpringDataIdentityTokenRepository repository;

    JpaIdentityTokenStore(SpringDataIdentityTokenRepository repository) {
        this.repository = repository;
    }

    @Override
    public IdentityToken save(IdentityToken token) {
        IdentityTokenEntity entity = repository.findById(token.id())
                .orElseGet(() -> IdentityTokenEntity.from(token));
        entity.updateFrom(token);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<IdentityToken> findByHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(IdentityTokenEntity::toDomain);
    }

    @Override
    public Optional<IdentityToken> findByHashForUpdate(String tokenHash) {
        return repository.findForUpdate(tokenHash).map(IdentityTokenEntity::toDomain);
    }

    @Override
    public void invalidateUnconsumedForUser(UUID userId, Instant now) {
        repository.consumeUnconsumed(userId, now);
    }
}
