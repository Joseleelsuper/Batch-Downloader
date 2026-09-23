package es.ubu.batchdownloader.identity.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

/** Consultas JPA de enlaces de acceso y bloqueo de consumo. */
interface SpringDataIdentityTokenRepository extends JpaRepository<IdentityTokenEntity, UUID> {
    Optional<IdentityTokenEntity> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select token from IdentityTokenEntity token where token.tokenHash = :hash")
    Optional<IdentityTokenEntity> findForUpdate(@Param("hash") String tokenHash);

    @Modifying
    @Query("update IdentityTokenEntity token set token.consumedAt = :now "
            + "where token.userId = :userId and token.consumedAt is null")
    void consumeUnconsumed(@Param("userId") UUID userId, @Param("now") Instant now);
}
