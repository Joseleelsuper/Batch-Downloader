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

/**
 * Implementa el componente {@code DownloadJobEntity}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Entity
@Table(name = "download_jobs")
class DownloadJobEntity {
    /**
     * Estado {@code id} mantenido por {@code DownloadJobEntity}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    /**
     * Estado {@code ownerId} mantenido por {@code DownloadJobEntity}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "owner_id", length = 36)
    private UUID ownerId;
    /**
     * Estado {@code anonymousOwnerHash} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "anonymous_owner_hash", length = 64)
    private String anonymousOwnerHash;
    /**
     * Estado {@code anonymousIpHash} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "anonymous_ip_hash", length = 64)
    private String anonymousIpHash;
    /**
     * Estado {@code status} mantenido por {@code DownloadJobEntity}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private DownloadJobStatus status;
    /**
     * Estado {@code progress} mantenido por {@code DownloadJobEntity}.
     */
    @Column(nullable = false)
    private int progress;
    /**
     * Estado {@code objectKey} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "object_key", length = 512)
    private String objectKey;
    /** Tamaño del ZIP publicado. */
    @Column(name = "artifact_size_bytes")
    private Long artifactSizeBytes;
    /** Huella SHA-256 del ZIP publicado. */
    @Column(name = "artifact_sha256", length = 64)
    private String artifactSha256;
    /**
     * Estado {@code failureCode} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "failure_code", length = 80)
    private String failureCode;
    /** Motivo temporal de espera por capacidad. */
    @Column(name = "wait_reason", length = 80)
    private String waitReason;
    /** Próximo instante de reintento por capacidad. */
    @Column(name = "retry_at")
    private Instant retryAt;
    /**
     * Estado {@code cancellationRequested} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "cancellation_requested", nullable = false)
    private boolean cancellationRequested;
    /**
     * Estado {@code notifyWhenReady} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "notify_when_ready", nullable = false)
    private boolean notifyWhenReady;
    /**
     * Estado {@code requestedCount} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "requested_count", nullable = false)
    private int requestedCount;
    /**
     * Estado {@code acceptedCount} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;
    /**
     * Estado {@code omittedCount} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "omitted_count", nullable = false)
    private int omittedCount;
    /**
     * Estado {@code createdAt} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    /**
     * Estado {@code updatedAt} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    /**
     * Estado {@code expiresAt} mantenido por {@code DownloadJobEntity}.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    /**
     * Estado {@code version} mantenido por {@code DownloadJobEntity}.
     */
    @Version
    private long version;
    /**
     * Estado {@code items} mantenido por {@code DownloadJobEntity}.
     */
    @OneToMany(mappedBy = "job", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<DownloadJobItemEntity> items = new ArrayList<>();

    /**
     * Inicializa una instancia de {@code DownloadJobEntity}.
     */
    protected DownloadJobEntity() {}

    /**
     * Ejecuta la operación {@code from}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     * @return Resultado producido por {@code from}.
     */
    static DownloadJobEntity from(DownloadJob job) {
        DownloadJobEntity entity = new DownloadJobEntity();
        entity.id = job.id();
        entity.updateFrom(job);
        entity.version = job.version();
        return entity;
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updateFrom}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     */
    void updateFrom(DownloadJob job) {
        ownerId = job.ownerId();
        anonymousOwnerHash = job.anonymousOwnerHash();
        anonymousIpHash = job.anonymousIpHash();
        status = job.status();
        progress = job.progress();
        objectKey = job.objectKey();
        artifactSizeBytes = job.artifactSizeBytes();
        artifactSha256 = job.artifactSha256();
        failureCode = job.failureCode();
        waitReason = job.waitReason();
        retryAt = job.retryAt();
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

    /**
     * Ejecuta la operación {@code mergeItems}.
     *
     * @param job Trabajo de descarga sobre el que se actúa.
     */
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

    /**
     * Convierte el valor recibido mediante {@code toDomain}.
     *
     * @return Resultado producido por {@code toDomain}.
     */
    DownloadJob toDomain() {
        return DownloadJob.rehydrate(
                id, ownerId, anonymousOwnerHash, anonymousIpHash,
                status, progress, objectKey, artifactSizeBytes, artifactSha256, waitReason, retryAt,
                failureCode, cancellationRequested, notifyWhenReady,
                requestedCount, acceptedCount, omittedCount, createdAt, updatedAt, expiresAt,
                items.stream().map(DownloadJobItemEntity::toDomain).toList(), version);
    }
}
