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
 * Persiste la fuente seleccionada y el progreso de un elemento vinculado a su trabajo mediante JPA.
 *
 * @author <a href="mailto:jgc1031@alu.ubu.es">José Gallardo Caballero</a>
 * @see es.ubu.batchdownloader.downloads.domain.DownloadJobItem
 * @see es.ubu.batchdownloader.downloads.infrastructure.persistence.DownloadJobEntity
 * @since 0.1.0
 * @version 0.1.0
 * @category Descargas
 */
@Entity
@Table(name = "download_job_items")
class DownloadJobItemEntity {
    /**
     * UUID estable del elemento.
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
     * Permite a JPA reconstruir un elemento de descarga desde sus columnas.
     */
    protected DownloadJobItemEntity() {}

    /**
     * Asocia una nueva entidad de elemento al trabajo y conserva su identidad y versión.
     *
     * @param item Elemento del dominio cuyos datos se trasladan a la entidad persistente.
     * @param job Entidad del trabajo al que pertenece el elemento.
     * @return entidad del elemento con su relación propietaria.
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
     * Copia selección, progreso, integridad y fechas del elemento sin cambiar identidad, trabajo ni
     * versión JPA.
     *
     * @param item Elemento del dominio cuyos datos se trasladan a la entidad persistente.
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
     * Rehidrata la selección y el estado persistido del elemento sin ejecutar otra transición.
     *
     * @return elemento del dominio con la versión guardada.
     */
    DownloadJobItem toDomain() {
        return DownloadJobItem.rehydrate(
                id, appId, sourceRef, appName, officialPageUrl,
                status, bytesDownloaded, sha256, errorCode, createdAt, updatedAt, version);
    }

    /**
     * Identifica el elemento al sincronizar la colección del agregado.
     *
     * @return UUID estable del elemento.
     */
    UUID id() { return id; }
}
