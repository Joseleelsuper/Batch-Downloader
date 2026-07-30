package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Define el contrato de {@code SpringDataIdentityTokenRepository}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
interface SpringDataIdentityTokenRepository extends JpaRepository<IdentityTokenEntity, UUID> {
    /**
     * Busca el resultado solicitado mediante {@code findByTokenHashAndType}.
     *
     * @param tokenHash Valor de {@code tokenHash} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     * @return Resultado producido por {@code findByTokenHashAndType}.
     */
    Optional<IdentityTokenEntity> findByTokenHashAndType(String tokenHash, IdentityToken.Type type);
    /**
     * Elimina el recurso solicitado mediante {@code deleteByUserIdAndTypeAndConsumedAtIsNull}.
     *
     * @param userId Identificador de {@code user} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     */
    void deleteByUserIdAndTypeAndConsumedAtIsNull(UUID userId, IdentityToken.Type type);
}
