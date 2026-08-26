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

/**
 * Implementa el componente {@code DownloadJobItemEntity}.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 */
@Entity
@Table(name = "download_job_items")
class DownloadJobItemEntity {
    /**
     * Estado {@code id} mantenido por {@code DownloadJobItemEntity}.
     */
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36, nullable = false)
    private UUID id;
    /**
     * Estado {@code job} mantenido por {@code DownloadJobItemEntity}.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_id", nullable = false)
    private DownloadJobEntity job;
    /**
     * Estado {@code appId} mantenido por {@code DownloadJobItemEntity}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "app_id", length = 36, nullable = false)
    private UUID appId;
    /**
     * Estado {@code sourceRef} mantenido por {@code DownloadJobItemEntity}.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "source_ref", length = 36)
    private UUID sourceRef;
    /**
     * Estado {@code appName} mantenido por {@code DownloadJobItemEntity}.
     */
    @Column(name = "app_name", length = 180)
    private String appName;
    /**
     * Estado {@code officialPageUrl} mantenido por {@code DownloadJobItemEntity}.
     */
    @Column(name = "official_url", length = 2048)
    private String officialPageUrl;
    /**
     * Estado {@code status} mantenido por {@code DownloadJobItemEntity}.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 24, nullable = false)
    private DownloadItemStatus status;
    /**
     * Estado {@code bytesDownloaded} mantenido por {@code DownloadJobItemEntity}.
     */
    @Column(name = "bytes_downloaded", nullable = false)
    private long bytesDownloaded;
    /**
     * Estado {@code sha256} mantenido por {@code DownloadJobItemEntity}.
     */
    @Column(length = 64)
    private String sha256;
    /**
     * Estado {@code errorCode} mantenido por {@code DownloadJobItemEntity}.
     */
    @Column(name = "error_code", length = 80)
    private String errorCode;
    /**
     * Estado {@code createdAt} mantenido por {@code DownloadJobItemEntity}.
     */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    /**
     * Estado {@code updatedAt} mantenido por {@code DownloadJobItemEntity}.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    /**
     * Estado {@code version} mantenido por {@code DownloadJobItemEntity}.
     */
    @Version
    private long version;

    /**
     * Inicializa una instancia de {@code DownloadJobItemEntity}.
     */
    protected DownloadJobItemEntity() {}

    /**
     * Ejecuta la operación {@code from}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     * @param job Trabajo de descarga sobre el que se actúa.
     * @return Resultado producido por {@code from}.
     */
    static DownloadJobItemEntity from(DownloadJobItem item, DownloadJobEntity job) {
        DownloadJobItemEntity entity = new DownloadJobItemEntity();
        entity.id = item.id();
        entity.job = job;
        entity.updateFrom(item);
        entity.version = item.version();
        return entity;
    }

    /**
     * Actualiza el recurso solicitado mediante {@code updateFrom}.
     *
     * @param item Elemento sobre el que se realiza la operación.
     */
    void updateFrom(DownloadJobItem item) {
        appId = item.appId();
        sourceRef = item.sourceRef();
        appName = item.appName();
        officialPageUrl = item.officialPageUrl();
        status = item.status();
        bytesDownloaded = item.bytesDownloaded();
        sha256 = item.sha256();
        errorCode = item.errorCode();
        createdAt = item.createdAt();
        updatedAt = item.updatedAt();
    }

    /**
     * Convierte el valor recibido mediante {@code toDomain}.
     *
     * @return Resultado producido por {@code toDomain}.
     */
    DownloadJobItem toDomain() {
        return DownloadJobItem.rehydrate(
                id, appId, sourceRef, appName, officialPageUrl,
                status, bytesDownloaded, sha256, errorCode, createdAt, updatedAt, version);
    }

    /**
     * Ejecuta la operación {@code id}.
     *
     * @return Resultado producido por {@code id}.
     */
    UUID id() { return id; }
}
