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
 * Mapea el agregado de descarga y sus elementos a JPA, conservando identidad y versión optimista al
 * reconstruir el dominio.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJob
 * @see es.ubu.batchdownloader.downloads.infrastructure.persistence.JpaDownloadJobStore
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
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
     * Permite a JPA reconstruir un trabajo y su colección persistida de elementos.
     */
    protected DownloadJobEntity() {}

    /**
     * Crea la entidad inicial a partir del agregado, incluida su identidad y versión.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
     * @return entidad nueva con elementos asociados.
     */
    static DownloadJobEntity from(DownloadJob job) {
        DownloadJobEntity entity = new DownloadJobEntity();
        entity.id = job.id();
        entity.updateFrom(job);
        entity.version = job.version();
        return entity;
    }

    /**
     * Copia el estado del agregado y sincroniza sus elementos sin sustituir la identidad ni la
     * versión gestionada por JPA.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
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
        requestedCount = job.requestedCount();
        acceptedCount = job.acceptedCount();
        omittedCount = job.omittedCount();
        createdAt = job.createdAt();
        updatedAt = job.updatedAt();
        expiresAt = job.expiresAt();
        mergeItems(job);
    }

    /**
     * Retira elementos ausentes, actualiza los existentes por UUID y asocia las nuevas entidades al
     * trabajo.
     *
     * @param job Agregado o vista persistida del trabajo cuya identidad y estado se procesan.
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
     * Rehidrata el trabajo con sus elementos y versión sin aplicar una nueva transición de estado.
     *
     * @return agregado independiente que refleja la entidad persistida.
     */
    DownloadJob toDomain() {
        return DownloadJob.rehydrate(
                id, ownerId, anonymousOwnerHash, anonymousIpHash,
                status, progress, objectKey, artifactSizeBytes, artifactSha256, waitReason, retryAt,
                failureCode, cancellationRequested,
                requestedCount, acceptedCount, omittedCount, createdAt, updatedAt, expiresAt,
                items.stream().map(DownloadJobItemEntity::toDomain).toList(), version);
    }
}
