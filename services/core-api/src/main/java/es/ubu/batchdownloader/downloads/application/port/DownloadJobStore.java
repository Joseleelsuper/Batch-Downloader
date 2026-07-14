package es.ubu.batchdownloader.downloads.application.port;

import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DownloadJobStore {
    DownloadJob save(DownloadJob job);
    Optional<DownloadJob> findById(UUID id);
    List<DownloadJob> findDownloadableExpiredBefore(Instant now);
    long countAnonymousNonTerminal(String anonymousOwnerHash);
    long countAnonymousCreatedSince(String anonymousOwnerHash, Instant createdAfter);
    long countAnonymousIpCreatedSince(String anonymousIpHash, Instant createdAfter);
}
