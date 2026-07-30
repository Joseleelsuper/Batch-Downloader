package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implementa el componente {@code JpaUserAccountStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
class JpaUserAccountStore implements UserAccountStore {
    /**
     * Estado {@code repository} mantenido por {@code JpaUserAccountStore}.
     */
    private final SpringDataUserAccountRepository repository;

    /**
     * Inicializa una instancia de {@code JpaUserAccountStore}.
     *
     * @param repository Repositorio utilizado por la operación.
     */
    JpaUserAccountStore(SpringDataUserAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * Implementa {@code existsByNormalizedUsername} para {@code JpaUserAccountStore}.
     *
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    @Override
    public boolean existsByNormalizedUsername(String normalizedUsername) {
        return repository.existsByNormalizedUsername(normalizedUsername);
    }

    /**
     * Implementa {@code existsByNormalizedEmail} para {@code JpaUserAccountStore}.
     *
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @return Indica si se cumple la condición evaluada.
     */
    @Override
    public boolean existsByNormalizedEmail(String normalizedEmail) {
        return repository.existsByNormalizedEmail(normalizedEmail);
    }

    /**
     * Busca el resultado solicitado mediante {@code findById}.
     *
     * @param id Identificador del recurso sobre el que se actúa.
     * @return Resultado producido por {@code findById}.
     */
    @Override
    public Optional<UserAccount> findById(UUID id) {
        return repository.findById(id).map(UserAccountEntity::toDomain);
    }

    /**
     * Busca el resultado solicitado mediante {@code findByNormalizedUsername}.
     *
     * @param normalizedUsername Valor de {@code normalizedUsername} utilizado por la operación.
     * @return Resultado producido por {@code findByNormalizedUsername}.
     */
    @Override
    public Optional<UserAccount> findByNormalizedUsername(String normalizedUsername) {
        return repository.findByNormalizedUsername(normalizedUsername).map(UserAccountEntity::toDomain);
    }

    /**
     * Busca el resultado solicitado mediante {@code findByNormalizedEmail}.
     *
     * @param normalizedEmail Valor de {@code normalizedEmail} utilizado por la operación.
     * @return Resultado producido por {@code findByNormalizedEmail}.
     */
    @Override
    public Optional<UserAccount> findByNormalizedEmail(String normalizedEmail) {
        return repository.findByNormalizedEmail(normalizedEmail).map(UserAccountEntity::toDomain);
    }

    /**
     * Guarda el recurso solicitado mediante {@code save}.
     *
     * @param account Valor de {@code account} utilizado por la operación.
     * @return Resultado producido por {@code save}.
     */
    @Override
    public UserAccount save(UserAccount account) {
        UserAccountEntity entity = repository.findById(account.id()).orElseGet(() -> UserAccountEntity.from(account));
        entity.updateFrom(account);
        return repository.save(entity).toDomain();
    }
}
