package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

interface SpringDataDownloadJobRepository extends JpaRepository<DownloadJobEntity, UUID> {
    @Override
    @EntityGraph(attributePaths = "items")
    Optional<DownloadJobEntity> findById(UUID id);

    @EntityGraph(attributePaths = "items")
    List<DownloadJobEntity> findByStatusInAndExpiresAtLessThanEqual(
            Collection<DownloadJobStatus> statuses, Instant expiresAt);

    long countByAnonymousOwnerHashAndStatusNotIn(
            String anonymousOwnerHash, Collection<DownloadJobStatus> statuses);

    long countByAnonymousOwnerHashAndCreatedAtGreaterThanEqual(
            String anonymousOwnerHash, Instant createdAt);

    long countByAnonymousIpHashAndCreatedAtGreaterThanEqual(
            String anonymousIpHash, Instant createdAt);
}
