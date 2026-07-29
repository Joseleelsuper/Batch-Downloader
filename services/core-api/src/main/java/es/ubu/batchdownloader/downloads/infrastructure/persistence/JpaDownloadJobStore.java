package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.application.port.DownloadJobStore;
import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
class JpaDownloadJobStore implements DownloadJobStore {
    private final SpringDataDownloadJobRepository repository;

    JpaDownloadJobStore(SpringDataDownloadJobRepository repository) {
        this.repository = repository;
    }

    @Override
    public DownloadJob save(DownloadJob job) {
        DownloadJobEntity entity = repository.findById(job.id()).orElseGet(() -> DownloadJobEntity.from(job));
        entity.updateFrom(job);
        return repository.save(entity).toDomain();
    }

    @Override
    public Optional<DownloadJob> findById(UUID id) {
        return repository.findById(id).map(DownloadJobEntity::toDomain);
    }

    @Override
    public List<DownloadJob> findDownloadableExpiredBefore(Instant now) {
        return repository.findByStatusInAndExpiresAtLessThanEqual(
                        List.of(
                                DownloadJobStatus.READY,
                                DownloadJobStatus.PARTIAL,
                                DownloadJobStatus.MANUAL_ONLY),
                        now)
                .stream().map(DownloadJobEntity::toDomain).toList();
    }

    @Override
    public long countAnonymousNonTerminal(String anonymousOwnerHash) {
        return repository.countByAnonymousOwnerHashAndStatusNotIn(
                anonymousOwnerHash,
                List.of(
                        DownloadJobStatus.READY,
                        DownloadJobStatus.PARTIAL,
                        DownloadJobStatus.MANUAL_ONLY,
                        DownloadJobStatus.FAILED,
                        DownloadJobStatus.CANCELLED,
                        DownloadJobStatus.EXPIRED));
    }

    @Override
    public long countAnonymousCreatedSince(String anonymousOwnerHash, Instant createdAfter) {
        return repository.countByAnonymousOwnerHashAndCreatedAtGreaterThanEqual(anonymousOwnerHash, createdAfter);
    }

    @Override
    public long countAnonymousIpCreatedSince(String anonymousIpHash, Instant createdAfter) {
        return repository.countByAnonymousIpHashAndCreatedAtGreaterThanEqual(anonymousIpHash, createdAfter);
    }
}
