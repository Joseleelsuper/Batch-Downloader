package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaUserAccountStore implements UserAccountStore {
    private final SpringDataUserAccountRepository repository;

    JpaUserAccountStore(SpringDataUserAccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public boolean existsByNormalizedUsername(String normalizedUsername) {
        return repository.existsByNormalizedUsername(normalizedUsername);
    }

    @Override
    public boolean existsByNormalizedEmail(String normalizedEmail) {
        return repository.existsByNormalizedEmail(normalizedEmail);
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return repository.findById(id).map(UserAccountEntity::toDomain);
    }

    @Override
    public Optional<UserAccount> findByNormalizedUsername(String normalizedUsername) {
        return repository.findByNormalizedUsername(normalizedUsername).map(UserAccountEntity::toDomain);
    }

    @Override
    public Optional<UserAccount> findByNormalizedEmail(String normalizedEmail) {
        return repository.findByNormalizedEmail(normalizedEmail).map(UserAccountEntity::toDomain);
    }

    @Override
    public UserAccount save(UserAccount account) {
        UserAccountEntity entity = repository.findById(account.id()).orElseGet(() -> UserAccountEntity.from(account));
        entity.updateFrom(account);
        return repository.save(entity).toDomain();
    }
}
