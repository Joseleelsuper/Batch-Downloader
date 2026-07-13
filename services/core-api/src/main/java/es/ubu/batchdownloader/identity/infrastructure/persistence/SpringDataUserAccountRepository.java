package es.ubu.batchdownloader.identity.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataUserAccountRepository extends JpaRepository<UserAccountEntity, UUID> {
    boolean existsByNormalizedUsername(String normalizedUsername);
    boolean existsByNormalizedEmail(String normalizedEmail);
    Optional<UserAccountEntity> findByNormalizedUsername(String normalizedUsername);
    Optional<UserAccountEntity> findByNormalizedEmail(String normalizedEmail);
}
