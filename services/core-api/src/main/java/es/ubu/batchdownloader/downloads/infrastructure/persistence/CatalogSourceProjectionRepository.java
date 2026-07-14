package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CatalogSourceProjectionRepository extends JpaRepository<CatalogSourceProjectionEntity, UUID> {
    Optional<CatalogSourceProjectionEntity> findFirstByAppIdAndTrustStatusOrderBySourceRef(UUID appId, String trustStatus);
}
