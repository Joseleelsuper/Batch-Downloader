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

/** Consultas bloqueantes y limpieza de solicitudes de acceso pendientes. */
interface SpringDataPendingMagicLinkRepository
        extends JpaRepository<PendingMagicLinkEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from PendingMagicLinkEntity request "
            + "where request.normalizedEmail = :email")
    Optional<PendingMagicLinkEntity> findByNormalizedEmailForUpdate(@Param("email") String normalizedEmail);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from PendingMagicLinkEntity request where request.tokenHash = :hash")
    Optional<PendingMagicLinkEntity> findByHashForUpdate(@Param("hash") String tokenHash);

    @Modifying
    @Query("delete from PendingMagicLinkEntity request where request.consumedAt is not null "
            + "or request.expiresAt <= :now")
    int deleteExpiredOrConsumed(@Param("now") Instant now);
}
