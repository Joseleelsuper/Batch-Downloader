package es.ubu.batchdownloader.downloads.infrastructure.persistence;

import es.ubu.batchdownloader.downloads.domain.DownloadItemStatus;
import es.ubu.batchdownloader.downloads.domain.DownloadJobItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "download_job_items")
class DownloadJobItemEntity {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private DownloadJobEntity job;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "app_id", length = 36, nullable = false)
    private UUID appId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_ref", length = 36, nullable = false)
    private UUID sourceRef;
    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private DownloadItemStatus status;
    @Column(name = "bytes_downloaded", nullable = false)
    private long bytesDownloaded;
    @Column(length = 64)
    private String sha256;
    @Column(name = "error_code", length = 80)
    private String errorCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected DownloadJobItemEntity() {}

    static DownloadJobItemEntity from(DownloadJobItem item, DownloadJobEntity job) {
        DownloadJobItemEntity entity = new DownloadJobItemEntity();
        entity.id = item.id();
        entity.job = job;
        entity.updateFrom(item);
        entity.version = item.version();
        return entity;
    }

    void updateFrom(DownloadJobItem item) {
        appId = item.appId();
        sourceRef = item.sourceRef();
        status = item.status();
        bytesDownloaded = item.bytesDownloaded();
        sha256 = item.sha256();
        errorCode = item.errorCode();
        createdAt = item.createdAt();
        updatedAt = item.updatedAt();
    }

    DownloadJobItem toDomain() {
        return DownloadJobItem.rehydrate(
                id, appId, sourceRef, status, bytesDownloaded, sha256, errorCode, createdAt, updatedAt, version);
    }

    UUID id() { return id; }
}
