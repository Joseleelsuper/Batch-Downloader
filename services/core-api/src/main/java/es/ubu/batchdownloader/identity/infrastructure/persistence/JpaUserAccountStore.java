package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.UserAccountStore;
import es.ubu.batchdownloader.identity.domain.UserAccount;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Adapta cuentas del dominio a JPA y sus consultas de identidad normalizada, conservando versión y
 * restricciones de unicidad.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.application.port.UserAccountStore
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.UserAccountEntity
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Repository
class JpaUserAccountStore implements UserAccountStore {
    /**
     * Estado {@code repository} mantenido por {@code JpaUserAccountStore}.
     */
    private final SpringDataUserAccountRepository repository;

    /**
     * Conecta la persistencia JPA de identidades y sus restricciones de unicidad.
     *
     * @param repository Repositorio JPA de la entidad correspondiente que participa en la
     *     transacción del llamador.
     */
    JpaUserAccountStore(SpringDataUserAccountRepository repository) {
        this.repository = repository;
    }

    /**
     * {@inheritDoc}
     *
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     */
    @Override
    public boolean existsByNormalizedUsername(String normalizedUsername) {
        return repository.existsByNormalizedUsername(normalizedUsername);
    }

    /**
     * {@inheritDoc}
     *
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     */
    @Override
    public boolean existsByNormalizedEmail(String normalizedEmail) {
        return repository.existsByNormalizedEmail(normalizedEmail);
    }

    /**
     * {@inheritDoc}
     *
     * @param id UUID estable del agregado que se consulta o reconstruye.
     */
    @Override
    public Optional<UserAccount> findById(UUID id) {
        return repository.findById(id).map(UserAccountEntity::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * @param normalizedUsername Nombre recortado y en minúsculas usado para búsquedas y unicidad.
     */
    @Override
    public Optional<UserAccount> findByNormalizedUsername(String normalizedUsername) {
        return repository.findByNormalizedUsername(normalizedUsername).map(UserAccountEntity::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * @param normalizedEmail Correo recortado y en minúsculas para consulta y unicidad.
     */
    @Override
    public Optional<UserAccount> findByNormalizedEmail(String normalizedEmail) {
        return repository.findByNormalizedEmail(normalizedEmail).map(UserAccountEntity::toDomain);
    }

    /**
     * Reutiliza la entidad de la cuenta cuando existe y sincroniza sus datos antes de guardar.
     *
     * @param account Agregado de cuenta que debe consultarse o persistirse.
     * @return cuenta reconstruida con la versión persistida.
     */
    @Override
    public UserAccount save(UserAccount account) {
        UserAccountEntity entity = repository.findById(account.id()).orElseGet(() -> UserAccountEntity.from(account));
        entity.updateFrom(account);
        return repository.save(entity).toDomain();
    }
}
