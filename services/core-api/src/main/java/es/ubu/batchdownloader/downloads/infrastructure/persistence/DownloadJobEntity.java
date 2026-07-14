package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.domain.DownloadJob;
import es.ubu.batchdownloader.downloads.domain.DownloadJobStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "download_jobs")
class DownloadJobEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "owner_id", length = 36)
    private UUID ownerId;
    @Column(name = "anonymous_owner_hash", length = 64)
    private String anonymousOwnerHash;
    @Column(name = "anonymous_ip_hash", length = 64)
    private String anonymousIpHash;
    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private DownloadJobStatus status;
    @Column(nullable = false)
    private int progress;
    @Column(name = "object_key", length = 512)
    private String objectKey;
    @Column(name = "failure_code", length = 80)
    private String failureCode;
    @Column(name = "cancellation_requested", nullable = false)
    private boolean cancellationRequested;
    @Column(name = "notify_when_ready", nullable = false)
    private boolean notifyWhenReady;
    @Column(name = "requested_count", nullable = false)
    private int requestedCount;
    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;
    @Column(name = "omitted_count", nullable = false)
    private int omittedCount;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Version
    private long version;
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DownloadJobItemEntity> items = new ArrayList<>();

    protected DownloadJobEntity() {}

    static DownloadJobEntity from(DownloadJob job) {
        DownloadJobEntity entity = new DownloadJobEntity();
        entity.id = job.id();
        entity.updateFrom(job);
        entity.version = job.version();
        return entity;
    }

    void updateFrom(DownloadJob job) {
        ownerId = job.ownerId();
        anonymousOwnerHash = job.anonymousOwnerHash();
        anonymousIpHash = job.anonymousIpHash();
        status = job.status();
        progress = job.progress();
        objectKey = job.objectKey();
        failureCode = job.failureCode();
        cancellationRequested = job.cancellationRequested();
        notifyWhenReady = job.notifyWhenReady();
        requestedCount = job.requestedCount();
        acceptedCount = job.acceptedCount();
        omittedCount = job.omittedCount();
        createdAt = job.createdAt();
        updatedAt = job.updatedAt();
        expiresAt = job.expiresAt();
        mergeItems(job);
    }

    private void mergeItems(DownloadJob job) {
        items.removeIf(entity -> job.items().stream().noneMatch(item -> item.id().equals(entity.id())));
        for (var item : job.items()) {
            DownloadJobItemEntity entity = items.stream()
                    .filter(candidate -> candidate.id().equals(item.id()))
                    .findFirst()
                    .orElseGet(() -> {
                        DownloadJobItemEntity created = DownloadJobItemEntity.from(item, this);
                        items.add(created);
                        return created;
                    });
            entity.updateFrom(item);
        }
    }

    DownloadJob toDomain() {
        return DownloadJob.rehydrate(
                id, ownerId, anonymousOwnerHash, anonymousIpHash,
                status, progress, objectKey, failureCode, cancellationRequested, notifyWhenReady,
                requestedCount, acceptedCount, omittedCount, createdAt, updatedAt, expiresAt,
                items.stream().map(DownloadJobItemEntity::toDomain).toList(), version);
    }
}
