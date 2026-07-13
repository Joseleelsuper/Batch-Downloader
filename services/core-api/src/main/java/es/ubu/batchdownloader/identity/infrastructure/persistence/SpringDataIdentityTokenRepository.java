package es.ubu.batchdownloader.identity.infrastructure.persistence;

import es.ubu.batchdownloader.identity.domain.IdentityToken;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataIdentityTokenRepository extends JpaRepository<IdentityTokenEntity, UUID> {
    Optional<IdentityTokenEntity> findByTokenHashAndType(String tokenHash, IdentityToken.Type type);
    void deleteByUserIdAndTypeAndConsumedAtIsNull(UUID userId, IdentityToken.Type type);
}
