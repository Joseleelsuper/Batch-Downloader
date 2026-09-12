package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.application.port.IdentityTokenStore;
import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import org.springframework.stereotype.Repository;

/**
 * Adapta persistencia y bloqueo JPA al contrato de emisión y consumo único de tokens de identidad.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.identity.application.port.IdentityTokenStore
 * @see es.ubu.batchdownloader.identity.infrastructure.persistence.SpringDataIdentityTokenRepository
 * @since 0.1.0
 * @version 0.1.0
 * @category Identidad
 */
@Repository
class JpaIdentityTokenStore implements IdentityTokenStore {
    /**
     * Estado {@code repository} mantenido por {@code JpaIdentityTokenStore}.
     */
    private final SpringDataIdentityTokenRepository repository;

    /**
     * Conecta las consultas y actualizaciones transaccionales de tokens.
     *
     * @param repository Repositorio JPA de la entidad correspondiente que participa en la
     *     transacción del llamador.
     */
    JpaIdentityTokenStore(SpringDataIdentityTokenRepository repository) {
        this.repository = repository;
    }

    /**
     * Actualiza la entidad existente o crea una nueva sin reemplazar una versión gestionada por
     * JPA.
     *
     * @param token Agregado de token que conserva hash, finalidad, vencimiento y consumo.
     * @return token reconstruido de la entidad guardada.
     */
    @Override
    public IdentityToken save(IdentityToken token) {
        IdentityTokenEntity entity = repository.findById(token.id()).orElseGet(() -> IdentityTokenEntity.from(token));
        entity.updateFrom(token);
        return repository.save(entity).toDomain();
    }

    /**
     * {@inheritDoc}
     *
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     */
    @Override
    public Optional<IdentityToken> findByHashAndType(String tokenHash, IdentityToken.Type type) {
        return repository.findByTokenHashAndType(tokenHash, type).map(IdentityTokenEntity::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * @param tokenHash SHA-256 hexadecimal del token opaco, utilizado para localizarlo sin
     *     almacenar su original.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     */
    @Override
    public Optional<IdentityToken> findByHashAndTypeForUpdate(String tokenHash, IdentityToken.Type type) {
        return repository.findForUpdate(tokenHash, type).map(IdentityTokenEntity::toDomain);
    }

    /**
     * {@inheritDoc}
     *
     * @param userId UUID canónico de la cuenta; no cambia al modificar su nombre visible.
     * @param type Finalidad del token: verificación de correo o restablecimiento de contraseña.
     * @param now Instante actual que se guarda en la transición o se compara con el vencimiento.
     */
    @Override
    public void invalidateUnconsumedForUser(UUID userId, IdentityToken.Type type, Instant now) {
        repository.consumeUnconsumed(userId, type, now);
    }
}
