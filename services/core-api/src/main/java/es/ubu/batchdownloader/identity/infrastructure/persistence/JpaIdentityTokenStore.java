package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implementa el componente {@code JpaIdentityTokenStore}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Repository
class JpaIdentityTokenStore implements IdentityTokenStore {
    /**
     * Estado {@code repository} mantenido por {@code JpaIdentityTokenStore}.
     */
    private final SpringDataIdentityTokenRepository repository;

    /**
     * Inicializa una instancia de {@code JpaIdentityTokenStore}.
     *
     * @param repository Repositorio utilizado por la operación.
     */
    JpaIdentityTokenStore(SpringDataIdentityTokenRepository repository) {
        this.repository = repository;
    }

    /**
     * Guarda el recurso solicitado mediante {@code save}.
     *
     * @param token Token utilizado para autorizar o correlacionar la operación.
     * @return Resultado producido por {@code save}.
     */
    @Override
    public IdentityToken save(IdentityToken token) {
        IdentityTokenEntity entity = repository.findById(token.id()).orElseGet(() -> IdentityTokenEntity.from(token));
        entity.updateFrom(token);
        return repository.save(entity).toDomain();
    }

    /**
     * Busca el resultado solicitado mediante {@code findByHashAndType}.
     *
     * @param tokenHash Valor de {@code tokenHash} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Resultado producido por {@code findByHashAndType}.
     */
    @Override
    public Optional<IdentityToken> findByHashAndType(String tokenHash, IdentityToken.Type type) {
        return repository.findByTokenHashAndType(tokenHash, type).map(IdentityTokenEntity::toDomain);
    }

    /**
     * Implementa {@code invalidateUnconsumedForUser} para {@code JpaIdentityTokenStore}.
     *
     * @param userId Identificador de {@code user} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     */
    @Override
    public void invalidateUnconsumedForUser(UUID userId, IdentityToken.Type type) {
        repository.deleteByUserIdAndTypeAndConsumedAtIsNull(userId, type);
    }
}
