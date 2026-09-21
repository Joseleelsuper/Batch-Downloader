package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.PendingMagicLinkStore;
import es.ubu.batchdownloader.identity.domain.PendingMagicLink;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** Adapta la tabla temporal de magic links al puerto de identidad. */
@Repository
class JpaPendingMagicLinkStore implements PendingMagicLinkStore {
    private final SpringDataPendingMagicLinkRepository repository;

    JpaPendingMagicLinkStore(SpringDataPendingMagicLinkRepository repository) {
        this.repository = repository;
    }

    @Override
    public PendingMagicLink save(PendingMagicLink request) {
        PendingMagicLinkEntity entity = repository.findById(request.id())
                .orElseGet(() -> PendingMagicLinkEntity.from(request));
        entity.updateFrom(request);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<PendingMagicLink> findByNormalizedEmailForUpdate(String normalizedEmail) {
        return repository.findByNormalizedEmailForUpdate(normalizedEmail)
                .map(PendingMagicLinkEntity::toDomain);
    }

    @Override
    public Optional<PendingMagicLink> findByHashForUpdate(String tokenHash) {
        return repository.findByHashForUpdate(tokenHash).map(PendingMagicLinkEntity::toDomain);
    }

    @Override
    public int deleteExpiredOrConsumed(Instant now) {
        return repository.deleteExpiredOrConsumed(now);
    }
}
