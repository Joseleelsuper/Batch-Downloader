package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from IdentityTokenEntity token where token.tokenHash = :hash and token.type = :type")
    Optional<IdentityTokenEntity> findForUpdate(
            @Param("hash") String tokenHash, @Param("type") IdentityToken.Type type);
    /**
     * Elimina el recurso solicitado mediante {@code deleteByUserIdAndTypeAndConsumedAtIsNull}.
     *
     * @param userId Identificador de {@code user} utilizado por la operación.
     * @param type Valor de {@code type} utilizado por la operación.
     */
    @Modifying
    @Query("update IdentityTokenEntity token set token.consumedAt = :now "
            + "where token.userId = :userId and token.type = :type and token.consumedAt is null")
    void consumeUnconsumed(
            @Param("userId") UUID userId,
            @Param("type") IdentityToken.Type type,
            @Param("now") Instant now);
}
